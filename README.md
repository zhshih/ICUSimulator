# ICU Simulator

IcuSimulator is a lightweight Java application that simulates ICU patient data and sends it to a WebSocket server. It is not a Spring Boot project, but uses WebSockets and Micrometer for simple metrics.

## Features

* Sends realistic ICU patient data (heartbeat, pulse, ECG readings) at configurable intervals. 
* Connects to a configurable WebSocket server. 
* Implements automatic reconnection with exponential backoff. 
* Tracks metrics with Micrometer: messages sent, failed, latency, reconnects. 
* Graceful shutdown via JVM shutdown hook.

## Usage

1. Start the ICUReceiver Spring Boot application first (or any WebSocket server listening at /ws/dynamic).
2. Run the simulator with:
   ```bash
   java -jar icu-simulator.jar
   ```

3. The simulator will:

   * Connect to the WebSocket server. 
   * Send JSON patient data periodically. 
   * Log connection events, sent messages, and errors. 

4. On JVM shutdown (Ctrl+C), the simulator stops sending and closes the WebSocket gracefully.

## Data Format

Each message sent over WebSocket is a JSON object like:

```json
{
"nationalId": "001",
"heartbeat": 72,
"pulse": 65,
"timestamp": "2025-11-09T15:30:25.123",
"ecgList": [0.0, 0.099, -0.05, 0.01, ...]
}
```

* heartbeat and pulse are random within physiological ranges. 
* ecgList simulates a sinusoidal ECG waveform with small noise and rare spikes.

## Reconnection

* On WebSocket closure or error, the simulator automatically reconnects with exponential backoff. 
* Prevents multiple concurrent reconnection attempts.