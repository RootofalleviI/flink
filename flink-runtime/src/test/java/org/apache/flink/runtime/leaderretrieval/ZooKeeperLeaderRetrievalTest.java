/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.leaderretrieval;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HighAvailabilityOptions;
import org.apache.flink.runtime.blob.VoidBlobStore;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.highavailability.zookeeper.ZooKeeperHaServices;
import org.apache.flink.runtime.jobmaster.JobMaster;
import org.apache.flink.runtime.leaderelection.LeaderElectionService;
import org.apache.flink.runtime.leaderelection.TestingContender;
import org.apache.flink.runtime.rpc.AddressResolution;
import org.apache.flink.runtime.rpc.RpcSystem;
import org.apache.flink.runtime.util.LeaderRetrievalUtils;
import org.apache.flink.runtime.util.TestingFatalErrorHandlerResource;
import org.apache.flink.runtime.util.ZooKeeperUtils;
import org.apache.flink.testutils.TestingUtils;
import org.apache.flink.testutils.executor.TestExecutorResource;
import org.apache.flink.util.TestLogger;

import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.assertEquals;

/** Tests for the ZooKeeper based leader election and retrieval. */
public class ZooKeeperLeaderRetrievalTest extends TestLogger {

    private static final RpcSystem RPC_SYSTEM = RpcSystem.load();

    @ClassRule
    public static final TestExecutorResource<ScheduledExecutorService> EXECUTOR_RESOURCE =
            TestingUtils.defaultExecutorResource();

    private TestingServer testingServer;

    private Configuration config;

    private HighAvailabilityServices highAvailabilityServices;

    @Rule
    public final TestingFatalErrorHandlerResource testingFatalErrorHandlerResource =
            new TestingFatalErrorHandlerResource();

    @Before
    public void before() throws Exception {
        testingServer = new TestingServer();

        config = new Configuration();
        config.setString(HighAvailabilityOptions.HA_MODE, "zookeeper");
        config.setString(
                HighAvailabilityOptions.HA_ZOOKEEPER_QUORUM, testingServer.getConnectString());

        highAvailabilityServices =
                new ZooKeeperHaServices(
                        ZooKeeperUtils.startCuratorFramework(
                                config, testingFatalErrorHandlerResource.getFatalErrorHandler()),
                        EXECUTOR_RESOURCE.getExecutor(),
                        config,
                        new VoidBlobStore());
    }

    @After
    public void after() throws Exception {
        if (highAvailabilityServices != null) {
            highAvailabilityServices.closeAndCleanupAllData();

            highAvailabilityServices = null;
        }

        if (testingServer != null) {
            testingServer.stop();

            testingServer = null;
        }
    }

    /**
     * Tests that LeaderRetrievalUtils.findConnectingAddress finds the correct connecting address in
     * case of an old leader address in ZooKeeper and a subsequent election of a new leader. The
     * findConnectingAddress should block until the new leader has been elected and his address has
     * been written to ZooKeeper.
     */
    @Test
    public void testConnectingAddressRetrievalWithDelayedLeaderElection() throws Exception {
        Duration timeout = Duration.ofMinutes(1L);

        long sleepingTime = 1000;

        LeaderElectionService leaderElectionService = null;
        LeaderElectionService faultyLeaderElectionService;

        ServerSocket serverSocket;
        InetAddress localHost;

        Thread thread;

        try {
            String wrongAddress =
                    RPC_SYSTEM.getRpcUrl(
                            "1.1.1.1",
                            1234,
                            "foobar",
                            AddressResolution.NO_ADDRESS_RESOLUTION,
                            config);

            try {
                localHost = InetAddress.getLocalHost();
                serverSocket = new ServerSocket(0, 50, localHost);
            } catch (UnknownHostException e) {
                // may happen if disconnected. skip test.
                System.err.println("Skipping 'testNetworkInterfaceSelection' test.");
                return;
            } catch (IOException e) {
                // may happen in certain test setups, skip test.
                System.err.println("Skipping 'testNetworkInterfaceSelection' test.");
                return;
            }

            InetSocketAddress correctInetSocketAddress =
                    new InetSocketAddress(localHost, serverSocket.getLocalPort());

            String correctAddress =
                    RPC_SYSTEM.getRpcUrl(
                            localHost.getHostName(),
                            correctInetSocketAddress.getPort(),
                            JobMaster.JOB_MANAGER_NAME,
                            AddressResolution.NO_ADDRESS_RESOLUTION,
                            config);

            faultyLeaderElectionService =
                    highAvailabilityServices.getJobManagerLeaderElectionService(
                            HighAvailabilityServices.DEFAULT_JOB_ID);
            TestingContender wrongLeaderAddressContender =
                    new TestingContender(wrongAddress, faultyLeaderElectionService);

            faultyLeaderElectionService.start(wrongLeaderAddressContender);

            FindConnectingAddress findConnectingAddress =
                    new FindConnectingAddress(
                            timeout,
                            highAvailabilityServices.getJobManagerLeaderRetriever(
                                    HighAvailabilityServices.DEFAULT_JOB_ID));

            thread = new Thread(findConnectingAddress);

            thread.start();

            leaderElectionService =
                    highAvailabilityServices.getJobManagerLeaderElectionService(
                            HighAvailabilityServices.DEFAULT_JOB_ID);
            TestingContender correctLeaderAddressContender =
                    new TestingContender(correctAddress, leaderElectionService);

            Thread.sleep(sleepingTime);

            faultyLeaderElectionService.stop();

            leaderElectionService.start(correctLeaderAddressContender);

            thread.join();

            InetAddress result = findConnectingAddress.getInetAddress();

            // check that we can connect to the localHost
            Socket socket = new Socket();
            try {
                // port 0 = let the OS choose the port
                SocketAddress bindP = new InetSocketAddress(result, 0);
                // machine
                socket.bind(bindP);
                socket.connect(correctInetSocketAddress, 1000);
            } finally {
                socket.close();
            }
        } finally {
            if (leaderElectionService != null) {
                leaderElectionService.stop();
            }
        }
    }

    /**
     * Tests that the LeaderRetrievalUtils.findConnectingAddress stops trying to find the connecting
     * address if no leader address has been specified. The call should return then
     * InetAddress.getLocalHost().
     */
    @Test
    public void testTimeoutOfFindConnectingAddress() throws Exception {
        Duration timeout = Duration.ofSeconds(1L);

        LeaderRetrievalService leaderRetrievalService =
                highAvailabilityServices.getJobManagerLeaderRetriever(
                        HighAvailabilityServices.DEFAULT_JOB_ID);
        InetAddress result =
                LeaderRetrievalUtils.findConnectingAddress(
                        leaderRetrievalService, timeout, RPC_SYSTEM);

        assertEquals(InetAddress.getLocalHost(), result);
    }

    static class FindConnectingAddress implements Runnable {

        private final Duration timeout;
        private final LeaderRetrievalService leaderRetrievalService;

        private InetAddress result;
        private Exception exception;

        public FindConnectingAddress(
                Duration timeout, LeaderRetrievalService leaderRetrievalService) {
            this.timeout = timeout;
            this.leaderRetrievalService = leaderRetrievalService;
        }

        @Override
        public void run() {
            try {
                result =
                        LeaderRetrievalUtils.findConnectingAddress(
                                leaderRetrievalService, timeout, RPC_SYSTEM);
            } catch (Exception e) {
                exception = e;
            }
        }

        public InetAddress getInetAddress() throws Exception {
            if (exception != null) {
                throw exception;
            } else {
                return result;
            }
        }
    }
}
