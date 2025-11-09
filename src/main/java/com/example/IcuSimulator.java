package com.example;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ICU Simulator sends simulated ICU patient vital data to the ICUReceiver service over WebSocket.
 *
 * <p>This simulator continuously generates and transmits synthetic ICU telemetry data
 * (e.g., heartbeat, pulse, and ECG readings) to a remote receiver service.
 * It is designed for resilience, observability, and safe reconnection handling.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li><b>Resilience:</b> Automatic reconnect with exponential backoff and
 *       clean resource reinitialization (WebSocket client is recreated on each reconnect).</li>
 *   <li><b>Thread safety:</b> Synchronization and atomic guards prevent concurrent
 *       reconnection and multiple data-sending timers.</li>
 *   <li><b>Observability:</b> Exposes Micrometer metrics for messages sent, failed,
 *       latency, and reconnect counts.</li>
 *   <li><b>Configurable:</b> Connection URI, national ID, and send interval can be set
 *       via system properties:
 *       <ul>
 *         <li>{@code -Dicu.ws.uri=ws://host:port/ws/dynamic}</li>
 *         <li>{@code -Dicu.nationalId=001}</li>
 *         <li>{@code -Dicu.sendIntervalMs=1000}</li>
 *       </ul>
 *   </li>
 *   <li><b>Realistic data:</b> Simulates dynamic ECG waveforms with occasional anomalies
 *       and random heart/pulse rates within physiological ranges.</li>
 *   <li><b>Graceful shutdown:</b> Stops active timers and closes the WebSocket connection cleanly.</li>
 * </ul>
 *
 * <h2>Reconnect Behavior</h2>
 * <ul>
 *   <li>When the WebSocket connection closes, a background thread attempts reconnection
 *       using exponential backoff (2s → 4s → 8s → up to 30s).</li>
 *   <li>Each reconnect creates a new {@link WebSocketClient} instance and restarts
 *       periodic data transmission once connected.</li>
 *   <li>Previous data-sending timers are explicitly canceled before reconnecting to prevent
 *       multiple concurrent threads from sending duplicate data.</li>
 *   <li>Only one reconnect attempt can run at a time, guarded by {@link AtomicBoolean}.</li>
 * </ul>
 *
 * <h2>Thread Model</h2>
 * <ul>
 *   <li>Data transmission runs on a single {@link java.util.Timer} background thread.</li>
 *   <li>Reconnect logic executes in a separate daemon thread, not inside WebSocket callbacks,
 *       ensuring thread isolation and stability.</li>
 *   <li>All timer start/stop operations are synchronized to prevent race conditions.</li>
 * </ul>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>
 *   java -Dicu.ws.uri=ws://localhost:8080/ws/dynamic \
 *        -Dicu.nationalId=ICU001 \
 *        -Dicu.sendIntervalMs=2000 \
 *        -cp icu-simulator.jar org.example.IcuSimulator
 * </pre>
 *
 * <p>Logs provide detailed insight into connection lifecycle, reconnection attempts,
 * data generation, and transmission outcomes.</p>
 */
public class IcuSimulator {

    private static final Logger logger = LoggerFactory.getLogger(IcuSimulator.class);

    private static final String WEBSOCKET_URI = System.getProperty("icu.ws.uri", "ws://localhost:8080/ws/dynamic");
    private static final String NATIONAL_ID = System.getProperty("icu.nationalId", "001");
    private static final long SEND_INTERVAL_MS = Long.parseLong(System.getProperty("icu.sendIntervalMs", "1000"));

    private static final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private static final Counter sentCounter = meterRegistry.counter("icu.simulator.messages.sent");
    private static final Counter failedCounter = meterRegistry.counter("icu.simulator.messages.failed");
    private static final Timer sendTimer = meterRegistry.timer("icu.simulator.messages.latency");
    private static final Counter reconnectCounter = meterRegistry.counter("icu.simulator.reconnects.total");

    private static WebSocketClient client;
    private static TimerTask sendTask;
    private static java.util.Timer timer;

    private static final AtomicBoolean reconnecting = new AtomicBoolean(false);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        connectWebSocket();
        setupShutdownHook();
    }

    /**
     * Connects to the WebSocket server and initiates the communication.
     *
     * <p>This method creates a new WebSocket client and attempts to connect asynchronously.
     * On successful connection, the periodic data sending is started.</p>
     *
     * @throws Exception if the WebSocket client fails to be created or connection fails
     */
    private static void connectWebSocket() throws Exception {
        client = createNewWebSocketClient();
        client.connect();
    }

    /**
     * Creates a fresh WebSocketClient instance with the required callbacks.
     *
     * <p>The returned client handles connection events such as open, message receipt,
     * closure, and errors.</p>
     *
     * <p>On connection open, it triggers the periodic data sending.</p>
     *
     * @return a new WebSocketClient instance connected to the configured URI
     * @throws Exception if the URI is invalid or client initialization fails
     */
    static WebSocketClient createNewWebSocketClient() throws Exception {
        URI uri = new URI(WEBSOCKET_URI);

        return new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                logger.info("WebSocket connected to {}", WEBSOCKET_URI);
                startSendingData();
            }

            @Override
            public void onMessage(String message) {
                logger.info("Received message: {}", message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                logger.warn("WebSocket closed (code={}, reason={}).", code, reason);
                new Thread(IcuSimulator::tryReconnect).start();
            }

            @Override
            public void onError(Exception ex) {
                logger.error("WebSocket error: {}", ex.getMessage(), ex);
            }
        };
    }

    /**
     * Starts the periodic sending of simulated ICU patient data.
     *
     * <p>This method stops any existing sending task before scheduling a new one
     * with the configured send interval.</p>
     *
     * <p>It is synchronized to prevent concurrent start/stop race conditions.</p>
     */
    static synchronized void startSendingData() {
        stopSendingData();

        ObjectMapper objectMapper = new ObjectMapper();
        timer = new java.util.Timer(true);

        sendTask = new TimerTask() {
            @Override
            public void run() {
                logger.debug("Preparing to send data: {}", generatePatientData());
                Map<String, Object> data = generatePatientData();
                try {
                    long start = System.nanoTime();
                    retrySend(objectMapper.writeValueAsString(data), 3);
                    sendTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                    sentCounter.increment();
                    logger.info("Sent data: {}", data);
                } catch (Exception e) {
                    failedCounter.increment();
                    logger.error("Failed to send ICU data: {}", e.getMessage(), e);
                }
            }
        };

        timer.scheduleAtFixedRate(sendTask, 0, SEND_INTERVAL_MS);
        logger.info("Started periodic data sending (interval={}ms)", SEND_INTERVAL_MS);
    }

    /**
     * Stops the current periodic data sending task and cancels the timer.
     *
     * <p>This method is synchronized to ensure thread-safe cancellation and
     * resource cleanup before starting a new sending task or reconnecting.</p>
     */
    static synchronized void stopSendingData() {
        if (sendTask != null) {
            sendTask.cancel();
            sendTask = null;
        }
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
        logger.info("Stopped data sending task.");
    }

    /**
     * Attempts to send a JSON message over the WebSocket connection with retries.
     *
     * <p>Retries sending up to {@code maxRetries} times using exponential backoff delays.</p>
     *
     * @param json       the JSON string to send
     * @param maxRetries the maximum number of retry attempts on failure
     * @throws InterruptedException if the thread is interrupted while sleeping between retries
     * @throws RuntimeException     if all retries fail to send the message
     */
    static void retrySend(String json, int maxRetries) throws InterruptedException {
        int attempt = 1;
        long delay = 500;
        while (attempt <= maxRetries) {
            try {
                client.send(json);
                return;
            } catch (Exception e) {
                logger.warn("Send attempt {} failed: {}", attempt, e.getMessage());
                Thread.sleep(delay);
                delay *= 2;
                attempt++;
            }
        }
        throw new RuntimeException("Max retries exceeded for sending message");
    }

    /**
     * Attempts to reconnect the WebSocket connection using exponential backoff.
     *
     * <p>This method prevents concurrent reconnect attempts using an atomic flag.
     * On successful reconnect, it restarts the periodic data sending.</p>
     *
     * <p>Previous WebSocket clients are closed and resources cleaned up before
     * creating a new client.</p>
     */
    static void tryReconnect() {
        if (!reconnecting.compareAndSet(false, true)) return;

        stopSendingData();

        int attempt = 1;
        long delay = 2000;

        while (true) {
            try {
                logger.info("🔄 Reconnecting attempt #{}...", attempt);

                if (client != null) {
                    try { client.closeBlocking(); } catch (Exception ignored) {}
                }

                client = createNewWebSocketClient();
                boolean ok = client.connectBlocking();
                if (ok && client.isOpen()) {
                    logger.info("Reconnected successfully on attempt #{}", attempt);
                    reconnectCounter.increment();
                    startSendingData();
                    break;
                }

                logger.warn("Reconnect attempt {} failed, socket not open", attempt);
            } catch (Exception e) {
                logger.error("Reconnect attempt {} failed: {}", attempt, e.getMessage());
            }

            attempt++;
            delay = Math.min(delay * 2, 30000);
            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
        }

        reconnecting.set(false);
    }

    /**
     * Generates a realistic ICU patient data map.
     *
     * <p>Data includes national ID, heartbeat, pulse, timestamp, and ECG readings.</p>
     *
     * @return a map representing the patient data to be sent
     */
    static Map<String, Object> generatePatientData() {
        Map<String, Object> data = new HashMap<>();
        data.put("nationalId", NATIONAL_ID);
        data.put("heartbeat", randomInRange(60, 100));
        data.put("pulse", randomInRange(50, 90));
        data.put("timestamp", LocalDateTime.now().toString());
        data.put("ecgList", generateECGList());
        return data;
    }

    /**
     * Generates a list of ECG readings with small noise and rare anomaly spikes.
     *
     * <p>The ECG values simulate a sinusoidal waveform with random noise,
     * occasionally including higher spikes to mimic anomalies.</p>
     *
     * @return a list of ECG readings as doubles
     */
    static List<Double> generateECGList() {
        List<Double> ecgList = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            double val = Math.sin(i * 0.1) +
                    ThreadLocalRandom.current().nextDouble(-0.05, 0.05);
            if (ThreadLocalRandom.current().nextDouble() < 0.01) {
                val += ThreadLocalRandom.current().nextDouble(0.5, 1.0);
            }
            ecgList.add(val);
        }
        return ecgList;
    }

    /**
     * Generates a random double value within the given range [min, max).
     *
     * @param min inclusive minimum value
     * @param max exclusive maximum value
     * @return a random double between min (inclusive) and max (exclusive)
     */
    static double randomInRange(double min, double max) {
        return ThreadLocalRandom.current().nextDouble(min, max);
    }


    /**
     * Adds a JVM shutdown hook that stops sending data and closes the WebSocket connection.
     *
     * <p>This ensures graceful simulator shutdown by cancelling timers and
     * closing open resources on JVM exit.</p>
     */
    private static void setupShutdownHook() {
        logger.info("Starting hooking setupShutdownHook");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received, stopping simulator...");
            if (sendTask != null) sendTask.cancel();
            if (timer != null) timer.cancel();
            if (client != null && client.isOpen()) client.close();
            logger.info("Simulator stopped cleanly.");
        }));
    }
}
