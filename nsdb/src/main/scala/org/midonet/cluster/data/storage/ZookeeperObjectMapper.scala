/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.data.storage

import java.util.ConcurrentModificationException
import java.util.concurrent.Executors._
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.{breakOut, mutable}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext._
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.codahale.metrics.MetricRegistry
import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.Message
import com.google.common.util.concurrent.SettableFuture

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent, CuratorEventType}
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException._
import org.apache.zookeeper.OpResult.ErrorResult
import org.apache.zookeeper._
import org.apache.zookeeper.data.Stat
import org.slf4j.{Logger, LoggerFactory}

import rx.Observable.OnSubscribe
import rx.{Notification, Observable, Subscriber}

import org.midonet.cluster.data.ZoomMetadata.ZoomOwner
import org.midonet.cluster.data.storage.CuratorUtil._
import org.midonet.cluster.data.ZoomMetadata.ZoomOwner
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.storage.ZoomSerializer.{createProvenance, deserialize, deserializerOf, serialize, updateProvenance}
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.data.{Obj, ObjId, getIdString}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.{NodeObservable, NodeObservableClosedException, PathCacheClosedException}
import org.midonet.util.{ImmediateRetriable, Retriable}
import org.midonet.util.concurrent.NamedThreadFactory
import org.midonet.util.functors.makeFunc1

/**
 * Object mapper that uses Zookeeper as a data store. Maintains referential
 * integrity through the use of field bindings, which must be declared
 * prior to any CRUD operations through the use of declareBinding().
 *
 * For example:
 *
 * declareBinding(Port.class, "bridgeId", CLEAR,
 * Bridge.class, "portIds", ERROR);
 *
 * This indicates that Port.bridgeId is a reference to Bridge.id
 * field, and that Bridge.portIds is a list of references to Port.id.
 * Each named field is assumed to be a reference (or list of references)
 * to the other classes "id" field (all objects must have a field named
 * "id", although it may be of any type.
 *
 * Whether the specified field is a single reference or list of references
 * is determined by reflectively examining the field to see whether its
 * type implements java.util.List.
 *
 * Consequently, when a port is created or updated with a new bridgeId
 * value, its id will be added to the corresponding bridge's portIds list.
 * CLEAR indicates that when a port is deleted its ID will be removed
 * from the portIds list of the bridge referenced by its bridgeId field.
 *
 * Likewise, when a bridge is created, the bridgeId field of any ports
 * referenced by portIds will be set to that bridge's ID, and when a bridge
 * is updated, ports no longer referenced by portIds will have their
 * bridgeId fields cleared, and ports newly referenced will have their
 * bridgeId fields set to the bridge's id. ERROR indicates that it is an
 * error to attempt to delete a bridge while its portIds field contains
 * references (i.e., while it has ports).
 *
 * Furthermore, if an object has a single-reference (non-list) field with
 * a non-null value, it is an error to create or update a third object in
 * a way that would cause that reference to be overwritten. For example, if
 * a port has a non-null bridge ID, then it is an error to attempt to create
 * another bridge whose portIds field contains that port's ID, as this would
 * effectively steal the port away from another bridge.
 *
 * A binding may be used to link two instances of the same type, as in the
 * case of linking ports:
 *
 * declareBinding(Port.class, "peerId", CLEAR,
 * Port.class, "peerId", CLEAR);
 *
 */
class ZookeeperObjectMapper(config: MidonetBackendConfig,
                            protected override val namespace: String,
                            protected override val curator: CuratorFramework,
                            metricsRegistry: MetricRegistry = new MetricRegistry)
    extends ZookeeperObjectState with Storage with StorageInternals {

    import ZookeeperObjectMapper._

    protected[storage] override val version = new AtomicLong(0)
    protected[cluster] val rootPath = config.rootKey
    protected[storage] val zoomPath = s"$rootPath/zoom"

    private[cluster] val basePath = s"$zoomPath/" + version.get
    private[storage] val topologyLockPath = s"$basePath/locks/zoom-topology"
    private[storage] val transactionLocksPath = basePath + s"/zoomlocks/lock"

    private[storage] val modelPath = basePath + s"/models"
    private[storage] val objectsPath = basePath + s"/objects"
    private val lock = new InterProcessSemaphoreMutex(curator, topologyLockPath)
    @volatile private var lockFree = false

    private val executor = newSingleThreadExecutor(
        new NamedThreadFactory("zoom", isDaemon = true))
    private implicit val executionContext = fromExecutorService(executor)

    private val objectObservableRef = new AtomicLong()

    private val simpleNameToClass = new mutable.HashMap[String, Class[_]]()
    private val objectObservables = new TrieMap[Key, ObjectObservable]
    private val classObservables = new TrieMap[Class[_], ClassObservable]

    /* Functions and variables to expose metrics using JMX in class
       ZoomMetrics. */
    implicit protected override val metrics =
        new StorageMetrics(this, metricsRegistry)

    metrics.connectionStateListeners.foreach {
        curator.getConnectionStateListenable.addListener
    }

    private[storage] def totalObjectObservableCount: Int =
        objectObservables.size
    private[storage] def totalClassObservableCount: Int =
        classObservables.size

    private[storage] def startedObjectObservableCount: Int =
        objectObservables.values.count(_.nodeObservable.isStarted)
    private[storage] def startedClassObservableCount: Int =
        classObservables.values.count(_.cache.isStarted)

    private[storage] def objectObservableCounters: Map[Class[_], Int] = {
        val map = new mutable.HashMap[Class[_], Int]
        val it = objectObservables.iterator
        while (it.hasNext) {
            val (key, obs) = it.next()
            if (obs.nodeObservable.isStarted) {
                map(key.clazz) = map.getOrElse(key.clazz, 0) + 1
            }
        }
        map.toMap
    }

    private[storage] def allClassObservables: Set[Class[_]] =
        classObservables.keySet.toSet
    private[storage] def startedClassObservables: Set[Class[_]] =
        classObservables.filter(_._2.cache.isStarted).keySet.toSet

    private[storage] def zkConnectionState: String =
        curator.getZookeeperClient.getZooKeeper.getState.toString
    /* End of functions and variable used for JMX monitoring. */

    /**
     * Manages objects referenced by the primary target of a create, update,
     * or delete operation.
     *
     * Caches all objects loaded during the operation. This is necessary
     * because an object may reference another object more than once. If we
     * reload the object from Zookeeper to add the second backreference, the
     * object loaded from Zookeeper will not have the first backreference
     * added. Since updates are not incremental, the first backreference will
     * be lost.
     */
    private class ZoomTransactionManager(owner: ZoomOwner)
            extends TransactionManager(classInfo.toMap, allBindings) {

        // This is a transaction-local cache of the raw object data.
        private val raw = new mutable.HashMap[Key, ObjRaw]

        // Create an ephemeral node so that we can get Zookeeper's current
        // ZXID. This will allow us to determine if any of the nodes we read
        // have been modified since the TransactionManager was created, allowing
        // us to ensure a consistent read across multiple nodes.
        private val (lockPath: String, zxid: Long) = try {
            val path = curator.create().creatingParentsIfNeeded()
                              .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
                              .forPath(transactionLocksPath)
            val stat = new Stat()
            curator.getData.storingStatIn(stat).forPath(path)
            (path, stat.getCzxid)
        } catch {
            case ex: Exception => throw new InternalObjectMapperException(
                "Could not acquire current zxid.", ex)
        }

        override def isRegistered(clazz: Class[_]): Boolean = {
            ZookeeperObjectMapper.this.isRegistered(clazz)
        }

        @throws[ConcurrentModificationException]
        @throws[NotFoundException]
        @throws[InternalObjectMapperException]
        protected override def getSnapshot(clazz: Class[_], id: ObjId)
        : ObjSnapshot = {
            val objPath = objectPath(clazz, id)
            val rawPath = altObjectPath(clazz, id)

            val objectFuture = asyncGet(objPath)
            val rawFuture = asyncGet(rawPath)

            val objectEvent = objectFuture.get()
            val rawEvent = rawFuture.get()

            if (objectEvent.getResultCode == Code.OK.intValue()) {
                if (objectEvent.getStat.getMzxid > zxid ||
                    (rawEvent.getResultCode == Code.OK.intValue() &&
                     rawEvent.getStat.getMzxid > zxid)) {
                    throw new ConcurrentModificationException(
                        s"${clazz.getSimpleName} with ID " +
                        s"${getIdString(id)} was modified during " +
                        s"the transaction.")
                }

                // Backwards compatibility: ignore if the raw node does not
                // exist.
                if (rawEvent.getResultCode == Code.OK.intValue()) {
                    raw.put(TransactionManager.getKey(clazz, id),
                            ObjRaw(rawEvent.getData, rawEvent.getStat.getVersion))
                } else if (rawEvent.getResultCode != Code.NONODE.intValue()) {
                    throw new InternalObjectMapperException(
                        KeeperException.create(Code.get(rawEvent.getResultCode),
                                               rawPath))
                }

                ObjSnapshot(deserialize(objectEvent.getData, clazz)
                                .asInstanceOf[Obj],
                            objectEvent.getStat.getVersion)
            } else if (objectEvent.getResultCode == Code.NONODE.intValue()) {
                throw new NotFoundException(clazz, id)
            } else {
                throw new InternalObjectMapperException(
                    KeeperException.create(Code.get(objectEvent.getResultCode),
                                           objPath))
            }
        }

        @throws[InternalObjectMapperException]
        protected override def getIds(clazz: Class[_]): Seq[ObjId] = {
            val path = classPath(clazz)
            val event = asyncGetChildren(path).get()

            if (event.getResultCode == Code.OK.intValue()) {
                event.getChildren.asScala
            } else {
                throw new InternalObjectMapperException(
                    KeeperException.create(Code.get(event.getResultCode), path))
            }
        }

        private def asyncGet(path: String): java.util.concurrent.Future[CuratorEvent] = {
            val future = SettableFuture.create[CuratorEvent]()
            curator.getData.inBackground(AsyncCallback, future).forPath(path)
            future
        }

        private def asyncGetChildren(path: String): java.util.concurrent.Future[CuratorEvent] = {
            val future = SettableFuture.create[CuratorEvent]()
            curator.getChildren.inBackground(AsyncCallback, future).forPath(path)
            future
        }

        /** Commits the operations from the current transaction to the storage
          * backend. */
        @throws[InternalObjectMapperException]
        @throws[ConcurrentModificationException]
        @throws[ReferenceConflictException]
        @throws[ObjectExistsException]
        @throws[StorageNodeExistsException]
        @throws[StorageNodeNotFoundException]
        override def commit(): Unit = {
            val ops = flattenOps
            val txn =
                curator.inTransaction().asInstanceOf[CuratorTransactionFinal]

            for ((key, txOp) <- ops) txOp match {
                case TxCreate(obj, change) =>
                    var path = objectPath(key.clazz, key.id)
                    Log.debug(s"Create: $path")
                    txn.create.forPath(path, serialize(obj))

                    path = altObjectPath(key.clazz, key.id)
                    Log.debug(s"Create: $path")
                    txn.create.forPath(path, createProvenance(owner, change,
                                                              version = 0))
                case TxUpdate(obj, ver, change) =>
                    var path = objectPath(key.clazz, key.id)
                    Log.debug(s"Update ($ver): $path")
                    txn.setData().withVersion(ver).forPath(path, serialize(obj))

                    path = altObjectPath(key.clazz, key.id)
                    raw.get(key) match {
                        case Some(ObjRaw(data, v)) =>
                            val d = updateProvenance(data, owner, change, ver + 1)
                            // Returns null if the provenance data has not
                            // changed.
                            if (d ne null) {
                                Log.debug(s"Update ($v): $path")
                                txn.setData().withVersion(v).forPath(path, d)
                            } else {
                                Log.debug(s"Skip update: $path")
                            }
                        case None =>
                            Log.debug(s"Create: $path")
                            txn.create.forPath(path, createProvenance(owner, change,
                                                                  ver + 1))
                    }
                case TxDelete(ver, change) =>
                    var path = objectPath(key.clazz, key.id)
                    Log.debug(s"Delete ($ver): $path")
                    txn.delete.withVersion(ver).forPath(path)

                    path = altObjectPath(key.clazz, key.id)
                    raw.get(key) match {
                        case Some(ObjRaw(_, v)) =>
                            Log.debug(s"Delete: $path")
                            txn.delete().withVersion(v).forPath(path)
                        case None =>
                    }
                case TxCreateNode(value) =>
                    Log.debug(s"Create node: ${key.id}")
                    txn.create.forPath(key.id, asBytes(value))
                case TxUpdateNode(value) =>
                    Log.debug(s"Update node: ${key.id}")
                    txn.setData().forPath(key.id, asBytes(value))
                case TxDeleteNode =>
                    Log.debug(s"Delete node: ${key.id}")
                    txn.delete.forPath(key.id)
                case TxNodeExists =>
                    throw new InternalObjectMapperException(
                        "TxNodeExists should have been filtered by flattenOps.")
            }

            val startTime = System.nanoTime()
            try {
                txn.commit()
            } catch {
                case bve: BadVersionException =>
                    // NoNodeException is assumed to be due to concurrent delete
                    // operation because we already successfully fetched any
                    // objects that are being updated.
                    throw new ConcurrentModificationException(bve)
                case e: KeeperException =>
                    rethrowException(ops, e)
                case rce: ReferenceConflictException =>
                    throw rce
                case NonFatal(ex) =>
                    throw new InternalObjectMapperException(ex)
            } finally {
                metrics.performance.addMultiLatency(System.nanoTime() - startTime)
            }
        }

        protected override def nodeExists(path: String): Boolean = {
            val stat = curator.checkExists.forPath(path)
            if ((stat ne null) && stat.getMzxid > zxid) {
                throw new ConcurrentModificationException(
                    s"Node $path was modified during the transaction.")
            }
            stat ne null
        }

        protected override def childrenOf(path: String): Seq[String] = {
            val prefix = if (path == "/") path else path + "/"
            try {
                curator.getChildren.forPath(path).asScala.map(prefix + _)
            } catch {
                case nne: NoNodeException => Seq.empty
            }
        }

        /**
          * Closes this transaction by releasing the transaction lock.
          */
        override def close(): Unit = {
            try curator.delete().forPath(lockPath)
            catch {
                // Not much we can do. Fortunately, it's ephemeral.
                case NonFatal(e) =>
                    Log.warn(s"Delete transaction lock node $lockPath failed", e)
            }
        }

        /**
          * Gets the descendants for the specified path in post-order
          * traversal to facilitate deletion.
          */
        private def descendantsOf(path: String): Seq[String] = {
            val children = childrenOf(path)
            val descendants = new mutable.MutableList[String]
            for (child <- children) {
                descendants ++= descendantsOf(child)
            }
            descendants += path
            descendants
        }

        /** Get a string as bytes, or null if the string is null. */
        private def asBytes(s: String) = if (s != null) s.getBytes else null

        /**
         * Returns the operation of this transaction that generated the given
         * exception.
         */
        private def opForException(ops: Seq[(Key, TxOp)], e: KeeperException)
        : (Key, TxOp) = {
            ops(e.getResults.asScala.indexWhere { case res: ErrorResult =>
                res.getErr == e.code.intValue })
        }

        /**
         * Given a [[Throwable]] and the list of operations submitted
         * for the transaction that produced the exception, throws an
         * appropriate exception, depending on the operation that caused the
         * exception.
         *
         * @throws ObjectExistsException if object creation failed.
         * @throws StorageNodeExistsException if node creation failed.
         * @throws StorageNodeNotFoundException if a node was not found.
         * @throws ConcurrentModificationException for all other cases.
         * @throws InternalObjectMapperException for all other cases.
         */
        @throws[ObjectExistsException]
        @throws[StorageNodeExistsException]
        @throws[StorageNodeNotFoundException]
        @throws[ConcurrentModificationException]
        @throws[InternalObjectMapperException]
        private def rethrowException(ops: Seq[(Key, TxOp)], e: Throwable)
        : Unit = e match {
            case e: NodeExistsException => opForException(ops, e) match {
                    case (Key(_, path), cm: TxCreateNode) =>
                        throw new StorageNodeExistsException(path)
                    case (key: Key, _) =>
                        throw new ObjectExistsException(key.clazz, key.id)
                    case _ =>
                        throw new InternalObjectMapperException(e)
                }
            case e: NoNodeException => opForException(ops, e) match {
                    case (Key(_, path), _: TxUpdateNode) =>
                        throw new StorageNodeNotFoundException(path)
                    case (Key(_, path), TxDeleteNode) =>
                        throw new StorageNodeNotFoundException(path)
                    case _ =>
                        throw new ConcurrentModificationException(e)
                }
            case e: NotEmptyException => opForException(ops, e) match {
                case (_, TxDeleteNode) =>
                    // We added operations to delete all descendants, so this
                    // should only happen if there was a concurrent
                    // modification.
                    throw new ConcurrentModificationException(e)
                case _ =>
                    throw new InternalObjectMapperException(e)
            }
            case _ =>
                throw new InternalObjectMapperException(e)
        }
    }

    private trait TransactionRetriable extends Retriable {

        override def maxRetries = config.transactionAttempts - 1

        @tailrec
        private def isRetriable(e: Throwable): Boolean = {
            e match {
                case null => false
                case _: ConcurrentModificationException => true
                case _ => isRetriable(e.getCause)
            }
        }

        protected override def handleRetry[T](e: Throwable, retries: Int,
                                              log: Logger,
                                              message: String): Unit = {
            // Unless the throwable is caused by a concurrent modification
            // throw immediately to stop retrying.
            if (!isRetriable(e))
                throw e
        }
    }
    private object TransactionRetriable
        extends TransactionRetriable with ImmediateRetriable

    /**
     * Registers the class for use. This method is not thread-safe, and
     * initializes a variety of structures which could not easily be
     * initialized dynamically in a thread-safe manner.
     *
     * Most operations require prior registration, including declareBinding.
     * Ideally this method should be called at startup for all classes
     * intended to be stored in this instance of ZookeeperObjectManager.
     */
    override def registerClass(clazz: Class[_]): Unit = {
        assert(!isBuilt)
        val name = clazz.getSimpleName
        simpleNameToClass.get(name) match {
            case Some(_) =>
                throw new IllegalStateException(
                    s"A class with the simple name $name is already " +
                    s"registered. Registering multiple classes with the same " +
                    s"simple name is not supported.")
            case None =>
                simpleNameToClass.put(name, clazz)
        }

        classInfo(clazz) = makeInfo(clazz)
        stateInfo(clazz) = new StateInfo
    }

    override def isRegistered(clazz: Class[_]): Boolean = {
        val registered = classInfo.contains(clazz)
        if (!registered)
            Log.warn(s"Class ${clazz.getSimpleName} is not registered.")
        registered
    }

    override def build(): Unit = {
        Log.info(s"Initializing NSDB version ${Storage.ProductVersion}:" +
                 s"${Storage.ProductCommit}")

        ensureClassNodes()
        lockFreeAndWatch()
        super.build()
    }

    /**
      * Enables the topology lock.
      */
    def enableLock(): Unit = {
        ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper, topologyLockPath)
    }

    /**
     * Ensures that the class nodes in Zookeeper for each provided class exist,
     * creating them if needed.
     */
    private def ensureClassNodes(): Unit = {
        val classes = classInfo.keySet
        assert(classes.forall(isRegistered))

        // First try a multi-check for all the classes. If they already exist,
        // as they usually will except on the first startup, we can verify this
        // in a single round trip to Zookeeper.
        var txn = curator.inTransaction().asInstanceOf[CuratorTransactionFinal]
        for (clazz <- classes) {
            txn = txn.check().forPath(classPath(clazz)).and()
            txn = txn.check().forPath(altClassPath(clazz)).and()
            txn = txn.check().forPath(stateClassPath(namespace, clazz)).and()
        }
        try {
            txn.commit()
            return
        } catch {
            case ex: Exception =>
                Log.info("Could not confirm existence of all class nodes in " +
                         "Zookeeper. Creating missing class node(s).")
        }

        // One or more didn't exist, so we'll have to check them individually.
        try {
            for (clazz <- classes) {
                ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper,
                               classPath(clazz))
                ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper,
                               altClassPath(clazz))
                ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper,
                               stateClassPath(namespace, clazz))
            }
        } catch {
            case ex: Exception => throw new InternalObjectMapperException(ex)
        }
    }

    /** Produce the instance of [[T]] deserialized from the event.
      *
      * Metrics-aware.
      */
    private def tryDeserialize[T](clazz: Class[T], id: ObjId,
                                  event: CuratorEvent): T = {
        if (event.getResultCode == Code.OK.intValue()) {
            deserialize(event.getData, clazz)
        } else if (event.getResultCode == Code.NONODE.intValue()) {
            metrics.error.objectNotFoundExceptionCounter.inc()
            throw new NotFoundException(clazz, id)
        } else {
            throw new InternalObjectMapperException(
                KeeperException.create(Code.get(event.getResultCode),
                                       event.getPath))
        }
    }

    @throws[ServiceUnavailableException]
    override def get[T](clazz: Class[T], id: ObjId): Future[T] = {
        assertBuilt()
        assert(isRegistered(clazz))
        val path = objectPath(clazz, id)
        val p = Promise[T]()
        val start = System.nanoTime()
        val cb = new BackgroundCallback {
            override def processResult(client: CuratorFramework,
                                       event: CuratorEvent): Unit = {
                metrics.performance.addLatency(event.getType, System.nanoTime() - start)
                try {
                    p.trySuccess(tryDeserialize(clazz, id, event))
                } catch {
                    case NonFatal(t) => p.tryFailure(t)
                }
            }
        }
        curator.getData.inBackground(cb).forPath(path)
        p.future
    }

    @throws[ServiceUnavailableException]
    override def getAll[T](clazz: Class[T], ids: Seq[_ <: ObjId])
    : Future[Seq[T]] = {
        assertBuilt()
        assert(isRegistered(clazz))
        Future.sequence(ids.map(get(clazz, _)))
    }

    /**
     * Gets all instances of the specified class from Zookeeper.
     */
    @throws[ServiceUnavailableException]
    override def getAll[T](clazz: Class[T]): Future[Seq[T]] = {
        assertBuilt()
        assert(isRegistered(clazz))

        val all = Promise[Seq[T]]()
        val start = System.nanoTime()
        val cb = new BackgroundCallback {
            override def processResult(client: CuratorFramework,
                                       evt: CuratorEvent): Unit = {
                val end = System.nanoTime()
                metrics.performance.addReadChildrenLatency(end - start)
                assert(CuratorEventType.CHILDREN == evt.getType)
                getAll(clazz, evt.getChildren).onComplete {
                    case Success(l) => all trySuccess l
                    case Failure(t) => all tryFailure t
                }
            }
        }

        val path = classPath(clazz)
        try {
            curator.getChildren.inBackground(cb).forPath(path)
        } catch {
            case ex: Exception => // Should have been created on build()
                throw new InternalObjectMapperException(
                    s"Node $path does not exist in Zookeeper.", ex)
        }
        all.future
    }

    /**
     * Returns true if the specified object exists in Zookeeper.
     */
    @throws[ServiceUnavailableException]
    override def exists(clazz: Class[_], id: ObjId): Future[Boolean] = {
        assertBuilt()
        assert(isRegistered(clazz))
        val p = Promise[Boolean]()
        val cb = new BackgroundCallback {
            override def processResult(client: CuratorFramework,
                                       evt: CuratorEvent): Unit = {
                assert(CuratorEventType.EXISTS == evt.getType)
                p.success(evt.getStat != null)
            }
        }
        try {
            curator.checkExists().inBackground(cb)
                   .forPath(objectPath(clazz, id))
        } catch {
            case ex: Exception => throw new InternalObjectMapperException(ex)
        }
        p.future
    }

    /**
     * Executes multiple create, update, and/or delete operations atomically.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ObjectReferencedException]
    @throws[ReferenceConflictException]
    @throws[ServiceUnavailableException]
    @throws[StorageNodeExistsException]
    @throws[StorageNodeNotFoundException]
    @throws[InternalObjectMapperException]
    override def multi(ops: Seq[PersistenceOp]): Unit = {
        assertBuilt()
        if (ops.isEmpty) return

        val manager = new ZoomTransactionManager(ZoomOwner.None)
        try {
            ops.foreach {
                case CreateOp(obj) =>
                    manager.create(obj)
                case UpdateOp(obj, validator) =>
                    manager.update(obj, validator)
                case DeleteOp(clazz, id, ignoresNeo) =>
                    manager.delete(clazz, id, ignoresNeo)
                case CreateNodeOp(path, value) =>
                    manager.createNode(path, value)
                case UpdateNodeOp(path, value) =>
                    manager.updateNode(path, value)
                case DeleteNodeOp(path) =>
                    manager.deleteNode(path)
            }
            manager.commit()
        } catch {
            case NonFatal(e) =>
                metrics.error.count(e)
                throw e
        } finally {
            manager.close()
        }
    }

    /**
      * Creates a new storage transaction that allows multiple read and write
      * operations to be executed atomically. The transaction guarantees that
      * the value of an object is not modified until the transaction is
      * completed or that the transaction will fail with a
      * [[java.util.ConcurrentModificationException]].
      */
    @throws[ServiceUnavailableException]
    override def transaction(owner: ZoomOwner): Transaction = {
        assertBuilt()
        new ZoomTransactionManager(owner)
    }

    /**
      * @see [[Storage.observable()]]
      */
    @throws[ServiceUnavailableException]
    override def observable[T](clazz: Class[T], id: ObjId): Observable[T] = {
        assertBuilt()
        assert(isRegistered(clazz))

        Observable.create(new OnSubscribe[T] {
            override def call(child: Subscriber[_ >: T]): Unit = {
                // Only request and subscribe to the internal, cache-able
                // observable when a child subscribes.
                internalObservable[T](clazz, id, version.get, OnCloseDefault)
                    .subscribe(child)
            }
        })
    }

    /**
      * Returns a cache-able, recoverable observable for the specified object.
      * If an observable for the object already exists in the cache, then
      * the method returns the same observable. Otherwise, the method creates
      * a new [[NodeObservable]] with an error handler and caches it, where the
      * close handler removes it from the cache.
      */
    protected override def internalObservable[T](clazz: Class[T], id: ObjId,
                                                 version: Long,
                                                 onClose: => Unit)
    : Observable[T] = {
        val key = Key(clazz, getIdString(id))
        val path = objectPath(clazz, id, version)

        objectObservables.getOrElse(key, {
            val ref = objectObservableRef.getAndIncrement()

            val nodeObservable = NodeObservable.create(
                curator, path, metrics, completeOnDelete = true, {
                    objectObservables.remove(key, ObjectObservable(ref))
                    onClose
                })

            val objectObservable = nodeObservable
                .map[Notification[T]](deserializerOf(clazz))
                .dematerialize().asInstanceOf[Observable[T]]
                .onErrorResumeNext(makeFunc1((t: Throwable) => t match {
                    case e: NodeObservableClosedException =>
                        metrics.error.objectObservableClosedCounter.inc()
                        internalObservable(clazz, id, version, OnCloseDefault)
                    case e: NoNodeException =>
                        metrics.error.objectNotFoundExceptionCounter.inc()
                        Observable.error(new NotFoundException(clazz, id))
                    case e: Throwable =>
                        metrics.error.objectObservableErrorCounter.inc()
                        Observable.error(e)
                }))

            val entry = ObjectObservable(ref, nodeObservable, objectObservable)

            objectObservables.putIfAbsent(key, entry).getOrElse(entry)
        }).objectObservable.asInstanceOf[Observable[T]]
    }

    /**
     * Refer to the interface documentation for functionality.
     *
     * This implementation involves a BLOCKING call when the observable is first
     * created, as we initialize the connection to ZK.
     */
    @throws[ServiceUnavailableException]
    override def observable[T](clazz: Class[T]): Observable[Observable[T]] = {
        assertBuilt()
        assert(isRegistered(clazz))

        classObservables.getOrElse(clazz, {
            val cache = new ClassSubscriptionCache(clazz, classPath(clazz),
                                                   curator, metrics)
            val obs = cache.observable
                .onErrorResumeNext(makeFunc1((t: Throwable) => t match {
                    case e: PathCacheClosedException =>
                        metrics.error.classObservableClosedCounter.inc()
                        classObservables.remove(clazz, ClassObservable(cache))
                        observable(clazz)
                    case e: Throwable =>
                        metrics.error.classObservableErrorCounter.inc()
                        Observable.error(e)
                }))
            val entry = ClassObservable(cache, obs)
            classObservables.putIfAbsent(clazz, entry).getOrElse(entry)
        }).clazz.asInstanceOf[Observable[Observable[T]]]
    }

    /**
      * @see[[Storage.tryTransaction()]]
      */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ObjectReferencedException]
    @throws[ReferenceConflictException]
    @throws[StorageException]
    override def tryTransaction[R](owner: ZoomOwner)(f: (Transaction) => R): R = {
        val lock =
            if (!lockFree) new InterProcessSemaphoreMutex(curator, topologyLockPath)
            else null
        if ((lock eq null) ||
            lock.acquire(config.lockTimeoutMs, TimeUnit.MILLISECONDS)) {
            try TransactionRetriable.retry(Log, "Transaction") {
                val tx = transaction(owner)
                try {
                    val result = f(tx)
                    tx.commit()
                    result
                } finally {
                    tx.close()
                }
            } finally {
                if (lock ne null) {
                    lock.release()
                }
            }
        } else {
            throw new StorageException("Acquiring lock timed-out after " +
                                       s"${config.lockTimeoutMs} ms")
        }
    }

    // We should have public subscription methods, but we don't currently
    // need them, and this is easier to implement for testing.
    @VisibleForTesting
    protected[storage] def getNodeValue(path: String): String = {
        val data = curator.getData.forPath(path)
        if (data == null) null else new String(data)
    }

    @VisibleForTesting
    protected[storage] def getNodeChildren(path: String): Seq[String] = {
        curator.getChildren.forPath(path).asScala
    }

    @inline
    private[storage] def classPath(clazz: Class[_]): String = {
        modelPath + "/" + clazz.getSimpleName
    }

    @inline
    protected[storage] override def objectPath(clazz: Class[_], id: ObjId,
                                               version: Long = version.longValue())
    : String = {
        classPath(clazz) + "/" + getIdString(id)
    }

    @inline
    protected[cluster] def altClassPath(clazz: Class[_]): String = {
        objectsPath + "/" + clazz.getSimpleName
    }

    @inline
    protected[cluster] def altObjectPath(clazz: Class[_], id: ObjId): String = {
        altClassPath(clazz) + "/" + getIdString(id)
    }

    protected[cluster] def isLockFree = lockFree

    private def lockFreeAndWatch(): Unit = synchronized {
        lockFree = curator.checkExists().usingWatcher(new Watcher {
            override def process(event: WatchedEvent): Unit = {
                ZookeeperObjectMapper.this.synchronized { lockFree = false }
            }
        }).forPath(topologyLockPath) eq null
    }

}

object ZookeeperObjectMapper {

    private[storage] final class MessageClassInfo(clazz: Class[_])
        extends ClassInfo(clazz) {

        val idFieldDesc =
            ProtoFieldBinding.getMessageField(clazz, FieldBinding.ID_FIELD)

        def idOf(obj: Obj) = obj.asInstanceOf[Message].getField(idFieldDesc)
    }

    private[storage] final class JavaClassInfo(clazz: Class[_])
        extends ClassInfo(clazz) {

        val idField = clazz.getDeclaredField(FieldBinding.ID_FIELD)

        idField.setAccessible(true)

        def idOf(obj: Obj) = idField.get(obj)
    }

    private case class ObjectObservable(ref: Long,
                                        nodeObservable: NodeObservable = null,
                                        objectObservable: Observable[_] = null) {
        override def equals(other: Any): Boolean = other match {
            case o: ObjectObservable => o.ref == ref
            case _ => false
        }
        override def hashCode: Int = ref.hashCode
    }

    private case class ClassObservable(cache: ClassSubscriptionCache[_],
                                       clazz: Observable[_] = null) {
        override def equals(other: Any): Boolean = other match {
            case o: ClassObservable => o.cache eq cache
            case _ => false
        }
        override def hashCode: Int = cache.hashCode
    }

    private case class ObjRaw(data: Array[Byte], version: Int)

    protected val Log = LoggerFactory.getLogger("org.midonet.nsdb")
    private val OnCloseDefault = { }

    private[storage] def makeInfo(clazz: Class[_])
    : ClassInfo = {
        try {
            if (classOf[Message].isAssignableFrom(clazz)) {
                new MessageClassInfo(clazz)
            } else {
                new JavaClassInfo(clazz)
            }
        } catch {
            case ex: Exception =>
                throw new IllegalArgumentException(
                    s"Class $clazz does not have a field named 'id', or the " +
                    "field could not be made accessible.", ex)
        }
    }

    private object AsyncCallback extends BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val future = event.getContext.asInstanceOf[SettableFuture[CuratorEvent]]
            future.set(event)
        }
    }

}
