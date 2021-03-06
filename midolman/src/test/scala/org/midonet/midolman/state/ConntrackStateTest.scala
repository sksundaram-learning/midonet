/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.midolman.state

import java.util.{Random, UUID}

import scala.collection.immutable.HashMap

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.simulation.BridgePort
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.odp.flows.FlowKeys
import org.midonet.packets.NatState.NatBinding
import org.midonet.packets.{IPv4Addr, MAC, Ethernet}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.{BaseShardedFlowStateTable, FlowStateTransaction}
import org.midonet.sdn.state.{OnHeapShardedFlowStateTable, OffHeapShardedFlowStateTable}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.collection.Reducer
import org.midonet.util.concurrent.MockClock

@RunWith(classOf[JUnitRunner])
abstract class ConntrackStateTest extends MidolmanSpec {

    val ping: Ethernet =
        { eth src MAC.random() dst MAC.random() } <<
        { ip4 src IPv4Addr.random dst IPv4Addr.random } <<
        { icmp.echo id 42000.toShort }

    val portId = UUID.randomUUID()
    val ingressDevice = UUID.randomUUID()
    val egressDevice = UUID.randomUUID()

    def connTrackStateTable: BaseShardedFlowStateTable[ConnTrackKey, ConnTrackValue]#FlowStateShard
    def connTrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue]

    override def beforeTest(): Unit = {
        val port = new BridgePort(id = portId, networkId = ingressDevice)
        VirtualTopology.add(portId, port)
    }

    def context(eth: Ethernet = ping, egressPort: UUID = null) = {
        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(eth))
        val ctx = PacketContext.generated(1, new Packet(eth, fmatch),
                                          fmatch, egressPort,
                                          cbRegistry=cbRegistry)
        ctx.initialize(connTrackTx,
                       new FlowStateTransaction[NatKey, NatBinding](null),
                       HappyGoLuckyLeaser,
                       new FlowStateTransaction[TraceKey, TraceContext](null))
        ctx.inputPort = portId
        ctx
    }

    feature("Connections are tracked") {
        scenario("Non-ip packets are considered forward flows") {
            val ctx = context({ eth src MAC.random() dst MAC.random() })
            ctx.isForwardFlow should be (true)
            connTrackTx.size() should be (0)
            ctx should be (taggedWith ())
        }

        scenario("Generated packets are treated as return flows") {
            val ctx = context(ping, UUID.randomUUID())
            ctx.isForwardFlow should be (false)
            connTrackTx.size() should be (0)
            ctx should be (taggedWith ())
        }

        scenario("Forward flows are tracked") {
            val ctx = context()

            val ingressKey = ConnTrackKey(ctx.wcmatch, ingressDevice)
            val egressKey = ConnTrackState.EgressConnTrackKey(ctx.wcmatch, egressDevice)

            ctx.isForwardFlow should be (true)
            ctx should be (taggedWith (ingressKey))

            ctx.trackConnection(egressDevice)
            ctx should be (taggedWith (ingressKey))

            connTrackTx.size() should be (1)
            val values = transactionValues(connTrackTx)

            values should contain theSameElementsAs Map(egressKey -> false)
            connTrackStateTable.getRefCount(egressKey) should be (0)
            connTrackTx.commit()
            connTrackStateTable.getRefCount(ingressKey) should be (0)
            connTrackStateTable.getRefCount(egressKey) should be (1)
        }

        scenario("Forward flows are recognized") {
            val ctx = context()

            val ingressKey = ConnTrackKey(ctx.wcmatch, ingressDevice)
            val egressKey = ConnTrackState.EgressConnTrackKey(ctx.wcmatch, egressDevice)

            connTrackStateTable.putAndRef(egressKey, false)

            ctx.isForwardFlow should be (true)
            ctx should be (taggedWith (ingressKey))

            ctx.trackConnection(egressDevice)
            ctx should be (taggedWith (ingressKey))

            connTrackTx.size() should be (1)
            val values = transactionValues(connTrackTx)

            values should contain theSameElementsAs Map(egressKey -> false)
            connTrackStateTable.getRefCount(egressKey) should be (1)
            connTrackTx.commit()
            connTrackStateTable.getRefCount(ingressKey) should be (0)
            connTrackStateTable.getRefCount(egressKey) should be (2)
        }

        scenario("Return flows are recognized") {
            val ctx = context()

            val ingressKey = ConnTrackKey(ctx.wcmatch, ingressDevice)

            connTrackStateTable.putAndRef(ingressKey, false)

            ctx.isForwardFlow should be (false)
            ctx should be (taggedWith ())
            ctx.trackConnection(egressDevice)
            ctx should be (taggedWith ())

            connTrackTx.size() should be (0)
        }
    }

    feature("Regressions") {
        scenario("Clear after resetContext failure") {
            val ctx = context()
            ctx.resetContext()
            ctx.clear()
        }
    }

    feature("Conntrack keys and values are serialized and deserialized") {
        scenario("conntrack keys are serialized and deserialized") {
            1.to(1000) foreach { i =>
                val r = new Random(i)
                val key = ConnTrackKey(IPv4Addr.random, r.nextInt.toShort,
                                       IPv4Addr.random, r.nextInt.toShort,
                                       r.nextInt.toByte, UUID.randomUUID)
                val serializer = new ConnTrackKeySerializer
                serializer.fromBytes(serializer.toBytes(key)) shouldBe key
            }
        }

        scenario("null keys are serialized and deserialized correctly") {
            val serializer = new ConnTrackKeySerializer
            serializer.fromBytes(serializer.toBytes(null)) shouldBe null
        }

        scenario("conntrack values are serialized and deserialized") {
            val serializer = new ConnTrackValueSerializer
            serializer.fromBytes(serializer.toBytes(true)) shouldBe true
            serializer.fromBytes(serializer.toBytes(false)) shouldBe false
            serializer.fromBytes(serializer.toBytes(null)) shouldBe null
        }
    }

    def transactionValues[K, V](tx: FlowStateTransaction[K, V]): HashMap[K, V] =
       tx.fold(new HashMap[K, V](),
               new Reducer[K, V, HashMap[K, V]] {
                    override def apply(acc: HashMap[K, V], key: K, value: V) =
                        acc + (key -> value)
                })
}

class OnHeapConntrackStateTest extends ConntrackStateTest {
    override val connTrackStateTable = new OnHeapShardedFlowStateTable[ConnTrackKey, ConnTrackValue]().addShard()
    override val connTrackTx = new FlowStateTransaction(connTrackStateTable)
}

class OffHeapConntrackStateTest extends ConntrackStateTest {
    override val connTrackStateTable = new OffHeapShardedFlowStateTable[ConnTrackKey, ConnTrackValue](
        new MockClock, new ConnTrackKeySerializer, new ConnTrackValueSerializer).addShard()
    override val connTrackTx = new FlowStateTransaction(connTrackStateTable)
}
