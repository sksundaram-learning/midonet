/*
 * Copyright 2017 Midokura SARL
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
package org.midonet.cluster.data.storage.cached

import rx.Observable

import org.midonet.cluster.data.ObjId
import org.midonet.cluster.data.storage._
import org.midonet.conf.HostIdGenerator
import org.midonet.util.functors.makeFunc1

class CachedStateStorage(private val store: Storage,
                         private val stateStore: StateStorage,
                         private val cache: Map[Class[_], Map[ObjId, Object]],
                         private val stateCache: Map[String, Map[Class[_], Map[ObjId, Map[String, StateKey]]]])
    extends CachedStorage(store, cache) with StateStorage {

    /** Adds a value to a key for the object with the specified class and
      * identifier to the state for the current namespace. The method is
      * asynchronous, returning an observable that when subscribed to will
      * execute the add and will emit one notification with the result of the
      * operation.
      *
      * @throws IllegalArgumentException    The key or class have not been
      * registered. */
    override def addValue(clazz: Class[_], id: ObjId, key: String,
                          value: String): Observable[StateResult] =
        notImplemented

    /** Removes a value from a key for the object with the specified class and
      * identifier from the state of the current namespace. For single value
      * keys, the `value` is ignored, and any current value is deleted. The
      * method is asynchronous, returning an observable that when subscribed to
      * will execute the remove and will emit one notification with the result
      * of the operation. */
    override def removeValue(clazz: Class[_], id: ObjId, key: String,
                             value: String): Observable[StateResult] =
        notImplemented

    /** Gets the set of values corresponding to a state key from the state of
      * the current namespace. The method is asynchronous, returning an
      * observable that when subscribed to will execute the get and will emit
      * one notification with the request result. */
    override def getKey(clazz: Class[_], id: ObjId,
                        key: String): Observable[StateKey] =
        getKey(namespace, clazz, id, key)

    /** The same as the previous `getKey`, except that this method returns
      * the state key value for the specified namespace. */
    override def getKey(namespace: String, clazz: Class[_], id: ObjId,
                        key: String): Observable[StateKey] =
        stateCache.get(namespace)
            .flatMap(_ get clazz)
            .flatMap(_ get id)
            .flatMap(_ get key) match {
                case Some(stateKey) =>
                    stateStore.getKey(clazz, id, key).startWith(stateKey)
                case None =>
                    stateStore.getKey(clazz, id, key)
            }

    /** Returns an observable for a state key of the current namespace. Upon
      * subscription, the observable will emit a notification with current set
      * of values corresponding to key and thereafter an additional notification
      * whenever the set of values has changed. The observable does not emit
      * notifications for successful write operations, which do not modify the
      * value set.
      * - If the namespace state does not exist, the observable completes
      * immediately.
      * - If the object class or object instance do not exist, the observable
      * completes immediately.
      * - If a value for the state key has not been set, the observable returns
      * a value option equal to [[None]].
      */
    override def keyObservable(clazz: Class[_], id: ObjId,
                               key: String): Observable[StateKey] =
        keyObservable(namespace, clazz, id, key)

    /** The same as the previous `keyObservable` method, except that this method
      * returns an observable for the state of the specified namespace. */
    override def keyObservable(namespace: String, clazz: Class[_], id: ObjId,
                               key: String): Observable[StateKey] =
        stateCache.get(namespace)
            .flatMap(_ get clazz)
            .flatMap(_ get id)
            .flatMap(_ get key) match {
                case Some(stateKey) =>
                    stateStore.keyObservable(namespace, clazz, id, key)
                        .startWith(stateKey)
                case None =>
                    stateStore.keyObservable(namespace, clazz, id, key)
            }

    /** The same as the previous `keyObservable` method, except that this method
      * returns an observable for the state of the last namespace identifier
      * emitted by the input `namespaces` observable.
      *
      * The output observable will not emit a notification until the input
      * observable emits at least one namespace identifier. If the specified
      * namespace state does not exist, the observable emits [[None]] as key
      * value. Therefore, the input observable can emit a non-existing namespace
      * identifier such as `null` to stop receiving updates from the last
      * emitted namespace state.
      */
    override def keyObservable(namespaces: Observable[String], clazz: Class[_],
                               id: ObjId, key: String): Observable[StateKey] =
        Observable.switchOnNext(namespaces map makeFunc1 { namespace =>
            keyObservable(namespace, clazz, id, key)
        })

    /** Returns a number uniquely identifying the current owner of the regular
      * session to storage.  Note that this value has nothing to do with the
      * node ID.
      */
    override def ownerId: Long = notImplemented

    /** Returns a number uniquely identifying the current owner of the fail
      * fast session to storage.  Note that this value has nothing to do with
      * the node ID.
      */
    override def failFastOwnerId: Long = notImplemented

    override protected val namespace: String =
        HostIdGenerator.getHostId.toString
}
