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
package org.midonet.midolman.util

import java.util.UUID
import java.util.{HashSet => JSet}

import scala.util.Random

import scala.collection.JavaConversions._

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.dhcp.{Host => DhcpHost}
import org.midonet.cluster.data.dhcp.Subnet
import org.midonet.cluster.data.dhcp.Subnet6

import org.midonet.cluster.state.LegacyStorage
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.{FragmentPolicy, Condition, NatTarget}
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.packets.{IPv4Addr, IPv4Subnet, TCP, MAC}
import org.midonet.cluster.data.l4lb.{PoolMember, Pool, VIP, LoadBalancer,
                                      HealthMonitor}
import org.midonet.midolman.state.l4lb.{PoolLBMethod, VipSessionPersistence, LBStatus}

trait VirtualConfigurationBuilders {

    def newHost(name: String, id: UUID, tunnelZones: Set[UUID]): UUID
    def newHost(name: String, id: UUID): UUID
    def newHost(name: String): UUID
    def isHostAlive(id: UUID): Boolean
    def newInboundChainOnBridge(name: String, bridge: UUID): UUID
    def newOutboundChainOnBridge(name: String, bridge: UUID): UUID
    def newInboundChainOnRouter(name: String, router: UUID): UUID
    def newOutboundChainOnRouter(name: String, router: UUID): UUID
    def newChain(name: String, id: Option[UUID] = None): UUID
    def newOutboundChainOnPort(name: String, port: UUID, id: UUID): UUID
    def newInboundChainOnPort(name: String, port: UUID, id: UUID): UUID
    def newOutboundChainOnPort(name: String, port: UUID): UUID
    def newInboundChainOnPort(name: String, port: UUID): UUID
    def newLiteralRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                              action: Action): UUID
    def newTcpDstRuleOnChain(
            chain: UUID, pos: Int, dstPort: Int, action: Action,
            fragmentPolicy: FragmentPolicy = FragmentPolicy.UNFRAGMENTED): UUID
    def newIpAddrGroupRuleOnChain(chain: UUID, pos: Int, action: Action,
                                  ipAddrGroupIdDst: Option[UUID],
                                  ipAddrGroupIdSrc: Option[UUID]): UUID
    def newForwardNatRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                 action: Action, targets: Set[NatTarget],
                                 isDnat: Boolean) : UUID
    def newReverseNatRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                         action: Action, isDnat: Boolean) : UUID
    def removeRuleFromBridge(bridge: UUID): Unit
    def newJumpRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                              jumpToChainID: UUID): UUID
    def newFragmentRuleOnChain(chain: UUID, pos: Int,
                               fragmentPolicy: FragmentPolicy,
                               action: Action): UUID
    def deleteRule(id: UUID): Unit
    def createIpAddrGroup(): UUID
    def createIpAddrGroup(id: UUID): UUID
    def addIpAddrToIpAddrGroup(id: UUID, addr: String): Unit
    def removeIpAddrFromIpAddrGroup(id: UUID, addr: String): Unit
    def deleteIpAddrGroup(id: UUID): Unit
    def greTunnelZone(name: String): UUID

    def newBridge(name: String): UUID
    def setBridgeAdminStateUp(bridge: UUID, state: Boolean): Unit
    def feedBridgeIp4Mac(bridge: UUID, ip: IPv4Addr, mac: MAC): Unit

    def newBridgePort(bridge: UUID,
                      host: Option[UUID] = None,
                      interface: Option[String] = None): UUID

    def setPortAdminStateUp(port: UUID, state: Boolean): Unit
    def deletePort(port: UUID, hostId: UUID): Unit
    def newPortGroup(name: String, stateful: Boolean = false): UUID
    def setPortGroupStateful(id: UUID, stateful: Boolean): Unit
    def newPortGroupMember(pgId: UUID, portId: UUID): Unit
    def deletePortGroupMember(pgId: UUID, portId: UUID): Unit

    def newRouter(name: String): UUID
    def setRouterAdminStateUp(router: UUID, state: Boolean): Unit

    def newRouterPort(router: UUID, mac: MAC, portAddr: String,
                      nwAddr: String, nwLen: Int): UUID
    def newRouterPort(router: UUID, mac: MAC, portAddr: IPv4Subnet): UUID

    def newRoute(router: UUID,
                 srcNw: String, srcNwLen: Int, dstNw: String, dstNwLen: Int,
                 nextHop: NextHop, nextHopPort: UUID, nextHopGateway: String,
                 weight: Int): UUID
    def deleteRoute(routeId: UUID): Unit
    def addDhcpSubnet(bridge : UUID,
                      subnet : Subnet): Unit
    def addDhcpHost(bridge : UUID, subnet : Subnet,
                    host : org.midonet.cluster.data.dhcp.Host): Unit
    def updatedhcpHost(bridge: UUID,
                       subnet: Subnet, host: DhcpHost): Unit
    def addDhcpSubnet6(bridge : UUID,
                       subnet : Subnet6): Unit
    def addDhcpV6Host(bridge : UUID, subnet : Subnet6,
                    host : org.midonet.cluster.data.dhcp.V6Host): Unit
    def linkPorts(port: UUID, peerPort: UUID): Unit
    def materializePort(port: UUID, hostId: UUID, portName: String): Unit
    def newCondition(
            nwProto: Option[Byte] = None,
            tpDst: Option[Int] = None,
            tpSrc: Option[Int] = None,
            ipAddrGroupIdDst: Option[UUID] = None,
            ipAddrGroupIdSrc: Option[UUID] = None,
            fragmentPolicy: FragmentPolicy = FragmentPolicy.UNFRAGMENTED)
            : Condition
    def newIPAddrGroup(id: Option[UUID]): UUID
    def addAddrToIpAddrGroup(id: UUID, addr: String): Unit
    def removeAddrFromIpAddrGroup(id: UUID, addr: String): Unit
    def newLoadBalancer(id: UUID = UUID.randomUUID): LoadBalancer
    def deleteLoadBalancer(id: UUID): Unit
    def setLoadBalancerOnRouter(loadBalancer: LoadBalancer, router: UUID): Unit
    def setLoadBalancerDown(loadBalancer: LoadBalancer): Unit
    def createVip(pool: Pool): VIP
    def createVip(pool: Pool, address: String, port: Int): VIP
    def deleteVip(vip: VIP): Unit
    def removeVipFromLoadBalancer(vip: VIP, loadBalancer: LoadBalancer): Unit
    def createRandomVip(pool: Pool): VIP
    def setVipPool(vip: VIP, pool: Pool): Unit
    def setVipAdminStateUp(vip: VIP, adminStateUp: Boolean): Unit
    def vipEnableStickySourceIP(vip: VIP): Unit
    def vipDisableStickySourceIP(vip: VIP): Unit
    def newHealthMonitor(id: UUID = UUID.randomUUID(),
                           adminStateUp: Boolean = true,
                           delay: Int = 2,
                           maxRetries: Int = 2,
                           timeout: Int = 2): HealthMonitor
    def newRandomHealthMonitor
            (id: UUID = UUID.randomUUID()): HealthMonitor
    def setHealthMonitorDelay(hm: HealthMonitor, delay: Int): Unit
    def deleteHealthMonitor(hm: HealthMonitor): Unit
    def newPool(loadBalancer: LoadBalancer,
                id: UUID = UUID.randomUUID,
                adminStateUp: Boolean = true,
                lbMethod: PoolLBMethod = PoolLBMethod.ROUND_ROBIN,
                hmId: UUID = null): Pool
    def setPoolHealthMonitor(pool: Pool, hmId: UUID): Unit
    def setPoolAdminStateUp(pool: Pool, adminStateUp: Boolean): Unit
    def setPoolLbMethod(pool: Pool, lbMethod: PoolLBMethod): Unit
    def newPoolMember(pool: Pool): PoolMember
    def newPoolMember(pool: Pool, address: String, port: Int,
                         weight: Int = 1): PoolMember
    def updatePoolMember(poolMember: PoolMember,
                         poolId: Option[UUID] = None,
                         adminStateUp: Option[Boolean] = None,
                         weight: Option[Integer] = None,
                         status: Option[LBStatus] = None): Unit
    def deletePoolMember(poolMember: PoolMember): Unit
    def setPoolMemberAdminStateUp(poolMember: PoolMember,
                                  adminStateUp: Boolean): Unit
    def setPoolMemberHealth(poolMember: PoolMember,
                            status: LBStatus): Unit
}

trait ForwardingVirtualConfigurationBuilders
        extends VirtualConfigurationBuilders {

    def virtConfBuilderImpl: VirtualConfigurationBuilders

    override def newHost(name: String, id: UUID, tunnelZones: Set[UUID]): UUID =
        virtConfBuilderImpl.newHost(name, id, tunnelZones)
    override def newHost(name: String, id: UUID): UUID =
        virtConfBuilderImpl.newHost(name, id)
    override def newHost(name: String): UUID =
        virtConfBuilderImpl.newHost(name)
    override def isHostAlive(id: UUID): Boolean =
        virtConfBuilderImpl.isHostAlive(id)

    override def newInboundChainOnBridge(name: String, bridge: UUID): UUID =
        virtConfBuilderImpl.newInboundChainOnBridge(name, bridge)
    override def newOutboundChainOnBridge(name: String, bridge: UUID): UUID =
        virtConfBuilderImpl.newOutboundChainOnBridge(name, bridge)
    override def newInboundChainOnRouter(name: String, router: UUID): UUID =
        virtConfBuilderImpl.newInboundChainOnRouter(name, router)
    override def newOutboundChainOnRouter(name: String, router: UUID): UUID =
        virtConfBuilderImpl.newOutboundChainOnRouter(name, router)
    override def newChain(name: String, id: Option[UUID] = None): UUID =
        virtConfBuilderImpl.newChain(name, id)
    override def newOutboundChainOnPort(name: String, port: UUID, id: UUID): UUID =
        virtConfBuilderImpl.newOutboundChainOnPort(name, port, id)
    override def newInboundChainOnPort(name: String, port: UUID, id: UUID): UUID =
        virtConfBuilderImpl.newInboundChainOnPort(name, port, id)
    override def newOutboundChainOnPort(name: String, port: UUID): UUID =
        virtConfBuilderImpl.newOutboundChainOnPort(name, port)
    override def newInboundChainOnPort(name: String, port: UUID): UUID =
        virtConfBuilderImpl.newInboundChainOnPort(name, port)
    override def newLiteralRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                       action: Action): UUID =
        virtConfBuilderImpl.newLiteralRuleOnChain(chain, pos, condition, action)
    override def newTcpDstRuleOnChain(
            chain: UUID, pos: Int, dstPort: Int, action: Action,
            fragmentPolicy: FragmentPolicy = FragmentPolicy.UNFRAGMENTED): UUID =
        virtConfBuilderImpl.newTcpDstRuleOnChain(chain, pos, dstPort, action, fragmentPolicy)
    override def newIpAddrGroupRuleOnChain(chain: UUID, pos: Int, action: Action,
                                           ipAddrGroupIdDst: Option[UUID],
                                           ipAddrGroupIdSrc: Option[UUID]): UUID =
        virtConfBuilderImpl.newIpAddrGroupRuleOnChain(chain, pos, action, ipAddrGroupIdDst, ipAddrGroupIdSrc)
    override def newForwardNatRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                          action: Action, targets: Set[NatTarget],
                                          isDnat: Boolean) : UUID =
        virtConfBuilderImpl.newForwardNatRuleOnChain(chain, pos, condition, action, targets, isDnat)
    override def newReverseNatRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                          action: Action, isDnat: Boolean) : UUID =
        virtConfBuilderImpl.newReverseNatRuleOnChain(chain, pos, condition, action, isDnat)
    override def removeRuleFromBridge(bridge: UUID): Unit =
        virtConfBuilderImpl.removeRuleFromBridge(bridge)
    override def newJumpRuleOnChain(chain: UUID, pos: Int, condition: Condition,
                                    jumpToChainID: UUID): UUID =
        virtConfBuilderImpl.newJumpRuleOnChain(chain, pos, condition, jumpToChainID)
    override def newFragmentRuleOnChain(chain: UUID, pos: Int,
                                        fragmentPolicy: FragmentPolicy,
                                        action: Action): UUID =
        virtConfBuilderImpl.newFragmentRuleOnChain(chain, pos, fragmentPolicy, action)
    override def deleteRule(id: UUID): Unit = virtConfBuilderImpl.deleteRule(id)
    override def createIpAddrGroup(): UUID = virtConfBuilderImpl.createIpAddrGroup()
    override def createIpAddrGroup(id: UUID): UUID = virtConfBuilderImpl.createIpAddrGroup(id)
    override def addIpAddrToIpAddrGroup(id: UUID, addr: String): Unit = virtConfBuilderImpl.addIpAddrToIpAddrGroup(id, addr)
    override def removeIpAddrFromIpAddrGroup(id: UUID, addr: String): Unit =
        virtConfBuilderImpl.removeIpAddrFromIpAddrGroup(id, addr)
    override def deleteIpAddrGroup(id: UUID): Unit = virtConfBuilderImpl.deleteIpAddrGroup(id)
    override def greTunnelZone(name: String): UUID = virtConfBuilderImpl.greTunnelZone(name)

    override def newBridge(name: String): UUID = virtConfBuilderImpl.newBridge(name)
    override def setBridgeAdminStateUp(bridge: UUID, state: Boolean): Unit =
        virtConfBuilderImpl.setBridgeAdminStateUp(bridge, state)
    override def feedBridgeIp4Mac(bridge: UUID, ip: IPv4Addr, mac: MAC): Unit =
        virtConfBuilderImpl.feedBridgeIp4Mac(bridge, ip, mac)

    override def newBridgePort(bridge: UUID,
                               host: Option[UUID] = None,
                               interface: Option[String] = None): UUID =
        virtConfBuilderImpl.newBridgePort(bridge, host, interface)

    override def setPortAdminStateUp(port: UUID, state: Boolean): Unit =
        virtConfBuilderImpl.setPortAdminStateUp(port, state)

    override def deletePort(port: UUID, hostId: UUID): Unit =
        virtConfBuilderImpl.deletePort(port, hostId)
    override def newPortGroup(name: String, stateful: Boolean = false): UUID =
        virtConfBuilderImpl.newPortGroup(name, stateful)
    override def setPortGroupStateful(id: UUID, stateful: Boolean): Unit =
        virtConfBuilderImpl.setPortGroupStateful(id, stateful)
    override def newPortGroupMember(pgId: UUID, portId: UUID): Unit =
        virtConfBuilderImpl.newPortGroupMember(pgId, portId)
    override def deletePortGroupMember(pgId: UUID, portId: UUID): Unit =
        virtConfBuilderImpl.deletePortGroupMember(pgId, portId)

    override def newRouter(name: String): UUID =
        virtConfBuilderImpl.newRouter(name)
    override def setRouterAdminStateUp(router: UUID, state: Boolean): Unit =
        virtConfBuilderImpl.setRouterAdminStateUp(router, state)

    override def newRouterPort(router: UUID, mac: MAC, portAddr: String,
                               nwAddr: String, nwLen: Int): UUID =
        virtConfBuilderImpl.newRouterPort(router, mac, portAddr, nwAddr, nwLen)
    override def newRouterPort(router: UUID, mac: MAC, portAddr: IPv4Subnet): UUID =
        virtConfBuilderImpl.newRouterPort(router, mac, portAddr)

    override def newRoute(router: UUID,
                          srcNw: String, srcNwLen: Int, dstNw: String, dstNwLen: Int,
                          nextHop: NextHop, nextHopPort: UUID, nextHopGateway: String,
                          weight: Int): UUID =
        virtConfBuilderImpl.newRoute(router, srcNw, srcNwLen, dstNw, dstNwLen,
                       nextHop, nextHopPort, nextHopGateway, weight)
    override def deleteRoute(routeId: UUID): Unit =
        virtConfBuilderImpl.deleteRoute(routeId)
    override def addDhcpSubnet(bridge : UUID,
                               subnet : Subnet): Unit =
        virtConfBuilderImpl.addDhcpSubnet(bridge, subnet)
    override def addDhcpHost(bridge : UUID, subnet : Subnet,
                             host : org.midonet.cluster.data.dhcp.Host): Unit =
        virtConfBuilderImpl.addDhcpHost(bridge, subnet, host)
    override def updatedhcpHost(bridge: UUID,
                                subnet: Subnet, host: DhcpHost): Unit =
        virtConfBuilderImpl.updatedhcpHost(bridge, subnet, host)
    override def addDhcpSubnet6(bridge : UUID,
                                subnet : Subnet6): Unit =
        virtConfBuilderImpl.addDhcpSubnet6(bridge, subnet)
    override def addDhcpV6Host(bridge : UUID, subnet : Subnet6,
                               host : org.midonet.cluster.data.dhcp.V6Host): Unit =
        virtConfBuilderImpl.addDhcpV6Host(bridge, subnet, host)
    override def linkPorts(port: UUID, peerPort: UUID): Unit =
        virtConfBuilderImpl.linkPorts(port, peerPort)
    override def materializePort(port: UUID, hostId: UUID, portName: String): Unit =
        virtConfBuilderImpl.materializePort(port, hostId, portName)
    override def newCondition(
            nwProto: Option[Byte] = None,
            tpDst: Option[Int] = None,
            tpSrc: Option[Int] = None,
            ipAddrGroupIdDst: Option[UUID] = None,
            ipAddrGroupIdSrc: Option[UUID] = None,
            fragmentPolicy: FragmentPolicy = FragmentPolicy.UNFRAGMENTED)
            : Condition =
        virtConfBuilderImpl.newCondition(nwProto, tpDst, tpSrc, ipAddrGroupIdDst,
                           ipAddrGroupIdSrc, fragmentPolicy)
    override def newIPAddrGroup(id: Option[UUID]): UUID =
        virtConfBuilderImpl.newIPAddrGroup(id)
    override def addAddrToIpAddrGroup(id: UUID, addr: String): Unit =
        virtConfBuilderImpl.addAddrToIpAddrGroup(id, addr)
    override def removeAddrFromIpAddrGroup(id: UUID, addr: String): Unit =
        virtConfBuilderImpl.removeAddrFromIpAddrGroup(id, addr)
    override def newLoadBalancer(id: UUID = UUID.randomUUID): LoadBalancer =
        virtConfBuilderImpl.newLoadBalancer(id)
    override def deleteLoadBalancer(id: UUID): Unit =
        virtConfBuilderImpl.deleteLoadBalancer(id)
    override def setLoadBalancerOnRouter(loadBalancer: LoadBalancer, router: UUID): Unit =
        virtConfBuilderImpl.setLoadBalancerOnRouter(loadBalancer, router)
    override def setLoadBalancerDown(loadBalancer: LoadBalancer): Unit =
        virtConfBuilderImpl.setLoadBalancerDown(loadBalancer)
    override def createVip(pool: Pool): VIP =
        virtConfBuilderImpl.createVip(pool)
    override def createVip(pool: Pool, address: String, port: Int): VIP =
        virtConfBuilderImpl.createVip(pool, address, port)
    override def deleteVip(vip: VIP): Unit =
        virtConfBuilderImpl.deleteVip(vip)
    override def removeVipFromLoadBalancer(vip: VIP, loadBalancer: LoadBalancer): Unit =
        virtConfBuilderImpl.removeVipFromLoadBalancer(vip, loadBalancer)
    override def createRandomVip(pool: Pool): VIP =
        virtConfBuilderImpl.createRandomVip(pool)
    override def setVipPool(vip: VIP, pool: Pool): Unit =
        virtConfBuilderImpl.setVipPool(vip, pool)
    override def setVipAdminStateUp(vip: VIP, adminStateUp: Boolean): Unit =
        virtConfBuilderImpl.setVipAdminStateUp(vip, adminStateUp)
    override def vipEnableStickySourceIP(vip: VIP): Unit =
        virtConfBuilderImpl.vipEnableStickySourceIP(vip)
    override def vipDisableStickySourceIP(vip: VIP): Unit =
        virtConfBuilderImpl.vipDisableStickySourceIP(vip)
    override def newHealthMonitor(id: UUID = UUID.randomUUID(),
                                  adminStateUp: Boolean = true,
                                  delay: Int = 2,
                                  maxRetries: Int = 2,
                                  timeout: Int = 2): HealthMonitor =
        virtConfBuilderImpl.newHealthMonitor(id, adminStateUp, delay, maxRetries, timeout)
    override def newRandomHealthMonitor
        (id: UUID = UUID.randomUUID()): HealthMonitor =
        virtConfBuilderImpl.newRandomHealthMonitor(id)
    override def setHealthMonitorDelay(hm: HealthMonitor, delay: Int): Unit =
        virtConfBuilderImpl.setHealthMonitorDelay(hm, delay)
    override def deleteHealthMonitor(hm: HealthMonitor): Unit =
        virtConfBuilderImpl.deleteHealthMonitor(hm)
    override def newPool(loadBalancer: LoadBalancer,
                         id: UUID = UUID.randomUUID,
                         adminStateUp: Boolean = true,
                         lbMethod: PoolLBMethod = PoolLBMethod.ROUND_ROBIN,
                         hmId: UUID = null): Pool =
        virtConfBuilderImpl.newPool(loadBalancer, id, adminStateUp, lbMethod, hmId)
    override def setPoolHealthMonitor(pool: Pool, hmId: UUID): Unit =
        virtConfBuilderImpl.setPoolHealthMonitor(pool, hmId)
    override def setPoolAdminStateUp(pool: Pool, adminStateUp: Boolean): Unit =
        virtConfBuilderImpl.setPoolAdminStateUp(pool, adminStateUp)
    override def setPoolLbMethod(pool: Pool, lbMethod: PoolLBMethod): Unit =
        virtConfBuilderImpl.setPoolLbMethod(pool, lbMethod)
    override def newPoolMember(pool: Pool): PoolMember =
        virtConfBuilderImpl.newPoolMember(pool)
    override def newPoolMember(pool: Pool, address: String, port: Int,
                               weight: Int = 1): PoolMember =
        virtConfBuilderImpl.newPoolMember(pool, address, port, weight)
    override def updatePoolMember(poolMember: PoolMember,
                                  poolId: Option[UUID] = None,
                                  adminStateUp: Option[Boolean] = None,
                                  weight: Option[Integer] = None,
                                  status: Option[LBStatus] = None): Unit =
        virtConfBuilderImpl.updatePoolMember(poolMember, poolId, adminStateUp,
                               weight, status)
    override def deletePoolMember(poolMember: PoolMember): Unit =
        virtConfBuilderImpl.deletePoolMember(poolMember)
    override def setPoolMemberAdminStateUp(poolMember: PoolMember,
                                           adminStateUp: Boolean): Unit =
        virtConfBuilderImpl.setPoolMemberAdminStateUp(poolMember, adminStateUp)
    override def setPoolMemberHealth(poolMember: PoolMember,
                                     status: LBStatus): Unit =
        virtConfBuilderImpl.setPoolMemberHealth(poolMember, status)
}
