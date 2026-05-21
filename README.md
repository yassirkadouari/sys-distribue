# Byzantine Consensus Résilient

## Project Structure
```
byzantine-consensus/
├── pom.xml                          # Maven build config
├── Dockerfile                       # Multi-stage Docker image
├── docker-compose.yml               # 4-node cluster orchestration
├── dashboard/                       # Real-time monitoring dashboard
│   ├── index.html                   # Dashboard UI
│   ├── style.css                    # Premium dark theme
│   ├── app.js                       # Real-time data visualization
│   └── nginx.conf                   # Nginx config
├── scripts/
│   └── generate-certs.sh            # TLS certificate generation
└── src/main/java/com/bft/
    ├── Main.java                    # Node entry point
    ├── config/NodeConfig.java       # Environment-based config
    ├── consensus/
    │   ├── PBFTEngine.java          # 3-phase PBFT protocol
    │   ├── ConsensusState.java      # Per-round state tracking
    │   └── ViewManager.java         # Leader rotation
    ├── crypto/
    │   ├── ECDSASigner.java         # ECDSA signing (BouncyCastle)
    │   ├── ShamirThreshold.java     # Threshold secret sharing
    │   └── TLSConfig.java           # TLS 1.3 mutual auth
    ├── network/
    │   ├── Message.java             # Protocol messages
    │   ├── NetworkManager.java      # TCP mesh networking
    │   └── MessageHandler.java      # Message dispatcher
    ├── byzantine/
    │   ├── ByzantineBehavior.java   # Attack interface
    │   ├── SilentNode.java          # Drop-all attack
    │   ├── EquivocationNode.java    # Contradictory messages
    │   └── ReplayNode.java          # Replay old messages
    ├── metrics/
    │   ├── MetricsCollector.java    # Performance tracking
    │   └── MetricsServer.java       # HTTP API for dashboard
    └── client/ClientSimulator.java  # Transaction generator
```

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.8+
- Docker & Docker Compose

### Run with Docker Compose
```bash
# Build and start 4-node cluster
docker-compose up --build

# Dashboard: http://localhost:3000
# Node 0 metrics: http://localhost:8080/metrics
# Node 1 metrics: http://localhost:8081/metrics
# Node 2 metrics: http://localhost:8082/metrics
# Node 3 metrics: http://localhost:8083/metrics
```

### Run Locally (without Docker)
```bash
# Build
mvn clean package

# Terminal 1: Node 0 (Leader)
NODE_ID=0 TOTAL_NODES=4 FAULT_TOLERANCE=1 NODE_PORT=5000 METRICS_PORT=8080 BYZANTINE_TYPE=none PEERS="0=localhost:5000,1=localhost:5001,2=localhost:5002,3=localhost:5003" java -jar target/byzantine-consensus-1.0.0.jar

# Terminal 2: Node 1
NODE_ID=1 TOTAL_NODES=4 FAULT_TOLERANCE=1 NODE_PORT=5001 METRICS_PORT=8081 BYZANTINE_TYPE=none PEERS="0=localhost:5000,1=localhost:5001,2=localhost:5002,3=localhost:5003" java -jar target/byzantine-consensus-1.0.0.jar

# Terminal 3: Node 2
NODE_ID=2 TOTAL_NODES=4 FAULT_TOLERANCE=1 NODE_PORT=5002 METRICS_PORT=8082 BYZANTINE_TYPE=none PEERS="0=localhost:5000,1=localhost:5001,2=localhost:5002,3=localhost:5003" java -jar target/byzantine-consensus-1.0.0.jar

# Terminal 4: Node 3 (Byzantine)
NODE_ID=3 TOTAL_NODES=4 FAULT_TOLERANCE=1 NODE_PORT=5003 METRICS_PORT=8083 BYZANTINE_TYPE=silent PEERS="0=localhost:5000,1=localhost:5001,2=localhost:5002,3=localhost:5003" java -jar target/byzantine-consensus-1.0.0.jar

# Open dashboard/index.html in browser
```

### Change Byzantine Attack Type
Edit `docker-compose.yml` → `node3` → `BYZANTINE_TYPE`:
- `none` — Honest node
- `silent` — Drops all messages (crash simulation)
- `equivocation` — Sends contradictory messages to different peers
- `replay` — Re-sends old consensus messages

## Architecture

### PBFT Protocol Flow
```
Client → Leader (PRE-PREPARE)
  → All Replicas (PREPARE)
    → When 2f PREPAREs: (COMMIT)
      → When 2f+1 COMMITs: EXECUTE
```

### Security
- **ECDSA Signatures** (secp256r1 via BouncyCastle) on every message
- **Anti-Replay** protection via message ID tracking
- **Shamir Secret Sharing** for threshold signatures
- **TLS 1.3** context preparation (mutual auth ready)
- **View Change** protocol for leader failure detection

### Fault Tolerance
- **n = 3f + 1** nodes required (4 nodes for f=1)
- Tolerates up to **f** Byzantine nodes
- Quorum: 2f PREPAREs, 2f+1 COMMITs

## Metrics API

Each node exposes HTTP endpoints:
| Endpoint | Description |
|---|---|
| `GET /metrics` | Full performance snapshot |
| `GET /status` | Node configuration and state |
| `GET /consensus` | Consensus round history |
| `GET /health` | Health check |
