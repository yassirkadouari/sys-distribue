# 🔄 Diagramme de Séquence - Consensus PBFT Résilient

Ce diagramme illustre le flux de messages lors d'un cycle complet de consensus PBFT au sein d'un cluster à 4 nœuds. Il met en scène :
*   Le client soumettant une transaction au Leader (Nœud 0).
*   La phase de **Pre-Prepare** initiée par le Leader.
*   La phase de **Prepare** avec quorum de préparation $2f = 2$ votes.
*   La phase de **Commit** avec quorum de validation $2f + 1 = 3$ votes.
*   L'exécution finale et la réponse au client.
*   Le comportement du **Nœud 3 (Byzantin Silencieux)** qui intercepte et détruit ses messages.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client Simulator
    participant N0 as Node 0 (Leader)
    participant N1 as Node 1 (Replica)
    participant N2 as Node 2 (Replica)
    participant N3 as Node 3 (Byzantine Silent)

    Note over Client, N3: --- PHASE DE REQUÊTE ---
    Client->>N0: submitTransaction("Alice -> Bob (50$)")

    Note over N0, N3: --- PHASE 1: PRE-PREPARE ---
    N0->>N0: Assigne seqNum=128
    N0->>N1: Broadcast PRE_PREPARE (seq=128, signature ECDSA)
    N0->>N2: Broadcast PRE_PREPARE (seq=128, signature ECDSA)
    N0->>N3: Broadcast PRE_PREPARE (seq=128, signature ECDSA)

    Note over N1, N3: Validation de la signature & de la vue

    Note over N0, N3: --- PHASE 2: PREPARE ---
    N1->>N0: Broadcast PREPARE (seq=128, signature)
    N1->>N2: Broadcast PREPARE (seq=128, signature)
    N1->>N3: Broadcast PREPARE (seq=128, signature)
    
    N2->>N0: Broadcast PREPARE (seq=128, signature)
    N2->>N1: Broadcast PREPARE (seq=128, signature)
    N2->>N3: Broadcast PREPARE (seq=128, signature)

    Note over N3: Intercepte le PREPARE et le détruit (Drop)
    N3--xN0: Broadcast PREPARE (seq=128) - DROPPED
    N3--xN1: Broadcast PREPARE (seq=128) - DROPPED
    N3--xN2: Broadcast PREPARE (seq=128) - DROPPED

    Note over N0: Quorum de Prepare atteint (2/2) -> PREPARED
    Note over N1: Quorum de Prepare atteint (2/2) -> PREPARED
    Note over N2: Quorum de Prepare atteint (2/2) -> PREPARED

    Note over N0, N3: --- PHASE 3: COMMIT ---
    N0->>N1: Broadcast COMMIT (seq=128, signature)
    N0->>N2: Broadcast COMMIT (seq=128, signature)
    N0->>N3: Broadcast COMMIT (seq=128, signature)

    N1->>N0: Broadcast COMMIT (seq=128, signature)
    N1->>N2: Broadcast COMMIT (seq=128, signature)
    N1->>N3: Broadcast COMMIT (seq=128, signature)

    N2->>N0: Broadcast COMMIT (seq=128, signature)
    N2->>N1: Broadcast COMMIT (seq=128, signature)
    N2->>N3: Broadcast COMMIT (seq=128, signature)

    Note over N3: Intercepte le COMMIT et le détruit (Drop)
    N3--xN0: Broadcast COMMIT (seq=128) - DROPPED
    N3--xN1: Broadcast COMMIT (seq=128) - DROPPED
    N3--xN2: Broadcast COMMIT (seq=128) - DROPPED

    Note over N0: Quorum de Commit atteint (3/3) -> COMMITTED
    Note over N1: Quorum de Commit atteint (3/3) -> COMMITTED
    Note over N2: Quorum de Commit atteint (3/3) -> COMMITTED

    Note over N0, N2: --- EXÉCUTION ---
    N0->>N0: Applique transaction à la machine d'état
    N1->>N1: Applique transaction à la machine d'état
    N2->>N2: Applique transaction à la machine d'état

    Note over N0, N2: --- RÉPONSE AU CLIENT ---
    N0->>Client: Broadcast REPLY (Executed, latency=12ms)
    N1->>Client: Broadcast REPLY (Executed, latency=10ms)
    N2->>Client: Broadcast REPLY (Executed, latency=14ms)
```
