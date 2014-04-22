/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.midonet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Objects;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.brain.southbound.midonet.handlers.MacPortUpdateHandler;
import org.midonet.brain.southbound.vtep.events.MacPortUpdate;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.MacPortMap;
import org.midonet.midolman.state.ReplicatedMap;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

/**
 * A proxy for MidoNet bridges.
 */
public class MidoVtep {
    private final static Logger log =
            LoggerFactory .getLogger(MidoVtep.class);

    // These are hard-coded for now.
    static final IPv4Addr midoIp = IPv4Addr.fromString("119.15.113.90");

    @Inject
    private DataClient dataClient = null;
    @Inject
    private MacPortUpdateHandler updateHandler;

    private Map<UUID, MacPortMap> macPortTables = null;
    private volatile boolean running = false;

    public boolean isRunning() {
        return this.running;
    }

    /**
     * Starts a MidoBridgesProxy. Looks up all the bridges that currently exist,
     * create local mac table copies that are automatically synchronized with
     * the backend, and registers watchers to the bridges.
     */
    public synchronized void start() {
        // If it's already running, silently return.
        if (this.running) return;

        Map<UUID, MacPortMap> macPortTables = new HashMap<UUID, MacPortMap>();
        try {
            List<UUID> bridgeIds = dataClient.bridgesGetAllIds();
            for (final UUID bridgeId : bridgeIds) {
                MacPortMap macTable =
                        // Creates a MAC table with persistent entries.
                        dataClient.bridgeGetMacTable(bridgeId,
                                                     Bridge.UNTAGGED_VLAN_ID,
                                                     false);
                macTable.addWatcher(new ReplicatedMap.Watcher<MAC, UUID>() {
                    public void processChange(
                            MAC mac, UUID oldPort, UUID newPort) {
                        MacPortUpdate update =
                                generateMacPortUpdate(mac, oldPort, newPort);
                        if (update != null) {
                            updateHandler.handleMacPortUpdate(update);
                        }
                    }
                });
                macTable.start();
                macPortTables.put(bridgeId, macTable);
            }
        } catch (StateAccessException ste) {
            log.error("Error retrieving virtual bridges: " + ste);
            this.running = false;
            return;
        } catch (SerializationException se) {
            log.error("Error serializing/deserializing port data: " + se);
            this.running = false;
            return;
        }

        this.macPortTables = macPortTables;
        this.running = true;
    }

    /**
     * Given a MAC address, an old assigned port ID, and a newly assigned port
     * ID, generates a MacPortUpdate for this MidoVtep.
     * @param mac A MAC address
     * @param oldPortID An old assigned port ID.
     * @param newValue A newly assigned port ID.
     * @return A MacPortUpdate for this MidoVtep.
     */
    private MacPortUpdate generateMacPortUpdate(MAC key, UUID oldPortID, UUID newValue) {
        if (oldPortID == null && newValue != null) {
            return new MacPortUpdate(key, null, midoIp);
        } else if (oldPortID != null && newValue == null) {
            return new MacPortUpdate(key, midoIp, null);
        } else {
            // Either both null (which should not happen) or non-null.
            // At the moment, we assume only a single Midolman so does nothing.
            return null;
        }
    }

    /**
     * Stops the MidoBridgesProxy. Clean up the local mac table copies.
     */
    public synchronized void stop() {
        for (MacPortMap macTable : this.macPortTables.values()) {
            macTable.stop();
        }
        this.macPortTables = null;
        this.running = false;
    }

    /*
     * Returns IDs of all the Mido bridges. Primarily for unit testing.
     * @return A set of all the Mido bridges.
     */
    Set<UUID> getMacTableOwnerIds() {
        return this.macPortTables.keySet();
    }

    /*
     * Tests whether there exists a corresponding MAC entry. Primarily for unit
     * testing.
     * @param bridgeId A bridge ID.
     * @param mac A MAC address.
     * @param portId A port UUID.
     * @return True if the entry exists, and false otherwise.
     */
    boolean containsMacEntry(UUID bridgeId, MAC mac, UUID portId) {
        MacPortMap macTable = this.macPortTables.get(bridgeId);
        if (macTable == null) {
            return false;
        }

        UUID pId = macTable.get(mac);
        return Objects.equal(portId, pId);
    }

    /*
     * Adds a new Mac entry. Primarily for unit testing.
     * @param bridgeId A bridge ID.
     * @param mac A MAC address.
     * @param portId A port UUID.
     * @return True if the entry was successfully added, false otherwise.
     */
    boolean addMacEntry(UUID bridgeId, MAC mac, UUID portId) {
        MacPortMap macTable = this.macPortTables.get(bridgeId);
        if (macTable == null) {
            return false;
        }

        macTable.put(mac, portId);
        return true;
    }
}
