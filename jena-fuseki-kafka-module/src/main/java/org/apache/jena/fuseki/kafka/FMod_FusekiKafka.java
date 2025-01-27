/*
 *  Copyright (c) Telicent Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.jena.fuseki.kafka;

import static org.apache.jena.kafka.FusekiKafka.LOG;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.atlas.lib.Pair;
import org.apache.jena.atlas.logging.FmtLog;
import org.apache.jena.atlas.logging.Log;
import org.apache.jena.fuseki.Fuseki;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.main.FusekiServer.Builder;
import org.apache.jena.fuseki.main.sys.FusekiModule;
import org.apache.jena.kafka.KConnectorDesc;
import org.apache.jena.kafka.KafkaConnectorAssembler;
import org.apache.jena.kafka.SysJenaKafka;
import org.apache.jena.kafka.common.DataState;
import org.apache.jena.kafka.common.PersistentState;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.shared.JenaException;
import org.apache.jena.sparql.core.assembler.AssemblerUtils;
import org.apache.jena.sparql.util.graph.GraphUtils;

public class FMod_FusekiKafka implements FusekiModule {

    private static AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private static void init() {
        boolean b = INITIALIZED.getAndSet(true);
        if ( b )
            // Already done.
            return;
        AssemblerUtils.registerAssembler(null, KafkaConnectorAssembler.getType(), new KafkaConnectorAssembler());
    }

    public FMod_FusekiKafka() {}

    // The Fuseki modules build lifecycle is same-thread.
    // This passes information from 'prepare' to 'server'
    // [BATCHER] Change in concurrent hash map (topic -> Pair<KConnectorDesc, DataState>>)
    private ThreadLocal<List<Pair<KConnectorDesc, DataState>>> buildState = ThreadLocal.withInitial(()->new ArrayList<>());

    @Override
    public String name() {
        return "FMod FusekiKafka";
    }

    protected String logMessage() {
        return String.format("Fuseki-Kafka Connector Module (%s)", SysJenaKafka.VERSION);
    }

    @Override
    public void prepare(FusekiServer.Builder builder, Set<String> names, Model configModel) {
        init();
        Log.info(Fuseki.configLog, logMessage());

        if ( configModel == null ) {
            // FmtLog.error(LOG, "No server configuration. Can't build connector");
            return ;
        }
        List<Resource> connectors = GraphUtils.findRootsByType(configModel, KafkaConnectorAssembler.getType());
        if ( connectors.isEmpty() ) {
            // FmtLog.error(LOG, "No connector in server configuration");
            return;
        }
        connectors.forEach(connector -> oneConnector(builder, connector, configModel));
    }

    /*package*/ void oneConnector(FusekiServer.Builder builder, Resource connector, Model configModel) {
        KConnectorDesc conn;
        try {
            conn = (KConnectorDesc)Assembler.general.open(connector);
        } catch (JenaException ex) {
            FmtLog.error(LOG, "Failed to build a connector", ex);
            return;
        }

        String dispatchURI = conn.getLocalDispatchPath();
        String remoteEndpoint = conn.getRemoteEndpoint();

//        // Endpoint to dataset.
//        String datasetName = datasetName(dispatchURI);
//        DatasetGraph dsg = builder.getDataset(datasetName);
//        if ( dsg == null )
//            throw new FusekiKafkaException("No datasets for '" + conn.getLocalEndpoint() + "'");
        PersistentState state = new PersistentState(conn.getStateFile());

        DataState dataState = DataState.restoreOrCreate(state, dispatchURI, remoteEndpoint, conn.getTopic());
        long lastOffset = dataState.getLastOffset();
        FmtLog.info(LOG, "Initial offset for topic %s = %d (%s)", conn.getTopic(), lastOffset, dispatchURI);
        recordConnector(builder, conn, dataState);
    }

    // Passing connector across stages by using a ThreadLocal.
    private void recordConnector(Builder builder, KConnectorDesc conn, DataState dataState) {
        Pair<KConnectorDesc, DataState> pair = Pair.create(conn, dataState);
        buildState.get().add(pair);
        FKRegistry.get().register(conn.getTopic(), conn);
    }

    private List<Pair<KConnectorDesc, DataState>> connectors(FusekiServer server) {
        return buildState.get();
    }

    /**
     * Add each connector, with the state tracker, to the server configured server.
     */
    @Override
    public void serverBeforeStarting(FusekiServer server) {
        // server(FusekiServer server) -- after build, before returning to builder caller
        // See also serverBeforeStarting which is an even later delayed setup point.
        List<Pair<KConnectorDesc, DataState>> connectors = connectors(server);
        if ( connectors == null )
            return;
        connectors.forEach(pair->{
            KConnectorDesc conn = pair.getLeft();
            DataState dataState = pair.getRight();
            FmtLog.info(LOG, "[%s] Starting connector between %s topic %s and endpoint %s",
                        conn.getTopic(),
                        conn.getBootstrapServers(), conn.getTopic(),
                        conn.dispatchLocal() ? conn.getLocalDispatchPath() : conn.getRemoteEndpoint());
            FKBatchProcessor batchProcessor = makeFKBatchProcessor(conn, server);
            FKS.addConnectorToServer(conn, server, dataState, batchProcessor);
        });
    }

    /** Clearup build state. */
    @Override
    public void serverAfterStarting(FusekiServer server) {
        // Clear up thread local.
        buildState.get().clear();
        buildState.remove();
    }

    /**
     * Make a {@link FKBatchProcessor} for the Fuseki Server being built. The default
     * is one that loops on the ConsumerRecords ({@link requestFK}) sending each to
     * the Fuseki server for dispatch. Other policies are possible such as
     * aggregating batches or directly applying to a dataset.
     */
    protected FKBatchProcessor makeFKBatchProcessor(KConnectorDesc conn, FusekiServer server) {
        return FKS.plainFKBatchProcessor(conn, server.getServletContext());
    }

    @Override
    public void serverStopped(FusekiServer server) {
        List<Pair<KConnectorDesc, DataState>> connectors = connectors(server);
        if ( connectors == null )
            return;
        connectors.forEach(pair->{
            KConnectorDesc conn = pair.getLeft();
            DataState dataState = pair.getRight();
            FKRegistry.get().unregister(conn.getTopic());
        });
    }
}
