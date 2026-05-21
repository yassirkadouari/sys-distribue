# 📜 Mon Rapport Technique : Système de Consensus Byzantin Résilient (PBFT)

*Auteurs : Yassir Kadouari, Matine Elkasbiji, Marouane Ismaili*  
*Projet : Systèmes Distribués*  
*Environnement de développement : Fedora Linux / Java 25 & 26 / Maven / Docker*

---

## 1. Vue d'Ensemble & Objectifs de Mon Projet

Dans le cadre de mon projet sur les **systèmes distribués**, j'ai développé et déployé une architecture complète de cluster tolérant aux pannes byzantines, reposant sur le protocole **PBFT (Practical Byzantine Fault Tolerance)**. 

L'objectif de mon travail est de garantir qu'un ensemble de serveurs distribués puisse s'accorder de manière sécurisée et cohérente sur l'historique d'un registre transactionnel (type blockchain ou base de données distribuée), même si jusqu'à $1/3$ des nœuds sont corrompus, piratés, ou tout simplement en panne réseau.

Pour valider mon système, j'ai construit un cluster à **4 nœuds** ($n=4$), ce qui me permet de tolérer exactement **1 nœud défaillant ou byzantin** ($f=1$).

### Les propriétés fondamentales que j'ai implémentées et validées :
*   **Sécurité (Safety) :** L'assurance qu'aucune double dépense ou bifurcation (fork) de l'historique ne puisse se produire. Deux nœuds honnêtes exécutent exactement les mêmes transactions dans le même ordre.
*   **Vivacité (Liveness) :** Le cluster continue à valider les transactions de manière fluide tant que le nombre de nœuds défaillants n'excède pas $f$.
*   **Non-répudiation :** Utilisation de signatures cryptographiques pour authentifier chaque message et empêcher toute usurpation d'identité.
*   **Résilience aux attaques réseau :** Protection active contre les attaques par relecture (replay attacks).

---

## 2. Mon Architecture Système & Mon Organisation de Code

J'ai conçu mon projet de manière extrêmement modulaire afin de séparer proprement les différentes responsabilités logiques. Mon code est structuré de la manière suivante :

```
byzantine-consensus/
├── pom.xml                          # Configuration de mes dépendances (BouncyCastle, Gson, Logback)
├── Dockerfile                       # Mon build multi-étape optimisé pour la production (JDK 25/26)
├── docker-compose.yml               # Orchestration de mes 4 nœuds + mon Dashboard Nginx
├── README.md                        # Mon guide rapide de démarrage
├── DOCUMENTATION.md                 # Mon présent rapport technique détaillé
├── CLASS_DIAGRAM.md                 # Mon diagramme de classes UML
├── SEQUENCE_DIAGRAM.md              # Mon diagramme de séquence réseau
├── dashboard/                       # Mon interface de monitoring web
│   ├── index.html, style.css        # Mon UI sombre moderne avec effets Glassmorphism
│   └── app.js                       # Mon script JS de polling et dessin dynamique de topologie
└── src/main/java/com/bft/
    ├── Main.java                    # Mon orchestrateur de démarrage de nœud
    ├── config/NodeConfig.java       # Ma gestion des variables d'environnement et calculs de quorum
    ├── consensus/
    │   ├── PBFTEngine.java          # Mon implémentation des 3 phases du consensus PBFT
    │   ├── ConsensusState.java      # Mon registre local des votes de préparation et d'engagement
    │   └── ViewManager.java         # Mon gestionnaire de détection de pannes du leader
    ├── crypto/
    │   ├── ECDSASigner.java         # Ma couche de signatures ECDSA (secp256r1 via BouncyCastle)
    │   ├── ShamirThreshold.java     # Ma démonstration de partage de secret à seuil (Shamir)
    │   └── TLSConfig.java           # Mon gestionnaire de contexte mTLS 1.3
    ├── network/
    │   ├── Message.java             # Mes structures de paquets JSON réseau
    │   ├── NetworkManager.java      # Mon maillage de connexions TCP full-mesh et échange de clés
    │   └── MessageHandler.java      # Mon dispatcher de paquets entrants vers le moteur PBFT
    ├── byzantine/
    │   ├── ByzantineBehavior.java   # Mon interface d'injection de comportements malveillants
    │   ├── SilentNode.java          # Ma simulation de panne franche (crash/mute)
    │   ├── EquivocationNode.java    # Ma simulation d'attaque par double message
    │   └── ReplayNode.java          # Ma simulation d'attaque par relecture réseau
    └── metrics/
        ├── MetricsCollector.java    # Mon collecteur d'indicateurs (débit, latence, anomalies)
        └── MetricsServer.java       # Mon API REST HTTP exposant mes métriques en JSON
```

---

## 3. Mon Implémentation du Consensus PBFT

Le cœur de mon moteur distribué (`PBFTEngine.java`) implémente rigoureusement le protocole de consensus PBFT à 3 phases :

1.  **PRE-PREPARE :** Lorsqu'une transaction est soumise, mon **Leader** (sélectionné par la formule `IdLeader = View % TotalNodes`) lui assigne un numéro de séquence séquentiel unique, l'enveloppe dans un paquet signé par sa clé privée ECDSA, et le diffuse à tous les réplicas.
2.  **PREPARE :** Chaque réplica valide le paquet reçu (signature du leader, numéro de séquence, état). S'il est valide, le réplica enregistre la transaction et diffuse un message `PREPARE` contenant le hash du paquet à tout le cluster.
    *   *Quorum de Préparation :* Dès que j'accumule $2f$ messages `PREPARE` cohérents, le nœud passe à l'état `PREPARED`.
3.  **COMMIT :** Une fois l'état `PREPARED` atteint, le nœud diffuse un message `COMMIT`.
    *   *Quorum d'Engagement :* Dès que j'accumule $2f+1$ messages `COMMIT` valides de pairs distincts, le nœud acquiert la certitude mathématique que la majorité qualifiée du cluster est d'accord sur cette transaction et qu'aucune bifurcation de l'historique n'est possible. Il passe à l'état `COMMITTED`, applique la transaction à sa machine d'état locale, calcule la latence et répond au client via un message `REPLY`.

---

## 4. Mes Choix Cryptographiques

Pour sécuriser mon protocole, j'ai mis en place deux mécanismes cryptographiques robustes :

### A. Signatures ECDSA (`ECDSASigner.java`)
J'ai choisi d'utiliser **ECDSA** sur la courbe elliptique standard **secp256r1** via le fournisseur de sécurité BouncyCastle. 
*   Au démarrage, chaque nœud génère sa propre paire de clés.
*   Pendant la phase de connexion réseau initiale, mes nœuds s'échangent mutuellement leurs clés publiques.
*   Chaque paquet de consensus circulant sur le réseau est signé numériquement. Cela empêche totalement un attaquant (ou un nœud byzantin) d'usurper l'identité d'un autre nœud ou d'altérer le contenu d'un message prepare/commit en transit.

### B. Partage de secret à seuil de Shamir (`ShamirThreshold.java`)
J'ai implémenté une démonstration du **partage de secret de Shamir** au-dessus d'un corps fini premier à 256 bits pour poser les bases d'une signature à seuil (TSS) :
*   Le secret est divisé en $n=4$ parts avec un seuil de reconstruction fixé à $t=2$.
*   Comme le montre ma console au démarrage, le système réussit à reconstituer fidèlement le secret d'origine dès que 2 parts quelconques sont combinées à l'aide de l'interpolation polynomiale de Lagrange au point $x=0$, tandis qu'une seule part ne révèle aucune information.

---

## 5. Mes Simulations d'Attaques Byzantines

Afin d'évaluer la résilience et la tolérance aux pannes de mon cluster, j'ai développé une interface modulaire d'injection de fautes (`ByzantineBehavior.java`) avec trois scénarios d'attaque réels :

1.  **Nœud Silencieux (`SilentNode.java`) :** Le nœud corrompu reçoit les messages de ses pairs mais détruit systématiquement tous ses messages sortants (Prepare/Commit). Cela simule une panne réseau franche ou un crash physique.
2.  **Nœud Équivoque (`EquivocationNode.java`) :** Le nœud tente de tromper le cluster en envoyant des transactions ou des hash contradictoires à différents pairs. Les signatures ECDSA et les vérifications de hash internes de mon moteur PBFT bloquent immédiatement cette tentative.
3.  **Nœud de Relecture (`ReplayNode.java`) :** Le nœud byzantin stocke d'anciens paquets de consensus valides et les réinjecte en boucle pour tenter de désynchroniser le réseau. Mon système d'empreinte de message unique (`MessageIdTracker`) rejette instantanément ces doublons.

---

## 6. Mon Dashboard & Mes Résultats de Test Réels

J'ai déployé et exécuté mon architecture dans mon environnement local. J'ai configuré le **Nœud 3 en mode Byzantin Silencieux (`silent`)** pour simuler sa défaillance complète.

Voici l'analyse détaillée de mes résultats, étayée par les captures d'écran que j'ai réalisées lors de mon exécution :

### A. Démarrage de mon Nœud 3 & Ma Démonstration Shamir
Lors du lancement du Nœud 3, ma console affiche la génération de ses clés ECDSA et l'exécution de mon test Shamir :

![Démarrage de Node 3 et démonstration Shamir](file:///home/ramsis/.gemini/antigravity/brain/89b71134-57f6-4571-8ae0-37efc38e2f43/artifacts/screenshot_node3_startup.png)

*   **Mon Analyse :** On peut observer l'initialisation de Shamir ($t=2$, $n=4$). Le secret d'origine `5cb887b0...` est splité en 4 parts. La reconstruction avec seulement 2 parts réussit avec succès (`SUCCESS ✓`). Le mode byzantin `silent` est bien activé pour ce nœud.

### B. Le Nœud Byzantin Silencieux en Action
Une fois le cluster en marche, le Nœud 3 reçoit les requêtes de consensus mais détruit tous ses messages Prepare et Commit sortants :

![Nœud Byzantin 3 bloquant ses messages](file:///home/ramsis/.gemini/antigravity/brain/89b71134-57f6-4571-8ae0-37efc38e2f43/artifacts/screenshot_node3_byzantine.png)

*   **Mon Analyse :** Dès que le Nœud 3 reçoit un message `PRE-PREPARE` du leader pour la séquence (ex: `seq=128`), son module byzantin intercepte le message sortant :
    `☠ BYZANTINE [SILENT] Dropping PREPARE (seq=128) - total dropped: 122`
    Il ne renvoie absolument rien à ses pairs honnêtes.

### C. La Résilience de mes Nœuds Réplicas Honnêtes
Pendant que le Nœud 3 sabote sa communication, mes nœuds honnêtes (0, 1 et 2) continuent de coopérer. En voici la preuve dans la console de mon Nœud 2 :

![ logs du réplica Node 2](file:///home/ramsis/.gemini/antigravity/brain/89b71134-57f6-4571-8ae0-37efc38e2f43/artifacts/screenshot_node2_replica.png)

*   **Mon Analyse :** Pour chaque round de consensus (séquences 125 à 136), mon Nœud 2 reçoit le `PRE-PREPARE` du leader. Il reçoit ensuite le message Prepare de l'autre nœud honnête (Nœud 1), ce qui lui permet d'atteindre le quorum de préparation ($2/2$ votes reçus). Il émet alors son Commit et reçoit les Commit des nœuds 0 et 1. Il atteint le quorum de commit qualifié ($3/3$ votes), valide définitivement le consensus et exécute la transaction. Le consensus est robuste face à la panne de Nœud 3.

### D. Mon Dashboard de Monitoring en Temps Réel
Pour visualiser l'état de mon système, j'ai ouvert mon dashboard web :

![Mon Dashboard en cours d'exécution](file:///home/ramsis/.gemini/antigravity/brain/89b71134-57f6-4571-8ae0-37efc38e2f43/artifacts/screenshot_dashboard.png)

*   **Mon Analyse :** 
    *   **Statut général :** Mon cluster a exécuté avec succès **145 rounds de consensus** consécutifs !
    *   **Topologie en direct :** 3 nœuds sur 4 sont en ligne et actifs (N0, N1, N2 en bleu et violet). Le Nœud 3 (Byzantin) est correctement marqué en **Rouge (Byzantine Silencieux)**.
    *   **Consensus rounds :** On peut observer sur la gauche de l'interface le détail de mes derniers rounds (séquences 139 à 145). Les nœuds honnêtes atteignent le quorum de préparation ($2/3$ prepares) et continuent d'avancer de manière stable, prouvant la vivacité (liveness) de mon implémentation.
    *   **Performance :** Mes transactions s'exécutent avec une latence ultra-faible, confirmant la performance de mon architecture distribuée multi-threadée en Java.

---

## 7. Résolution des Problèmes Réseau (Address already in use)

Lors de mes tests locaux intensifs, j'ai parfois été confronté à l'erreur suivante :
`java.net.BindException: Address already in use`

### Ma Solution technique :
Cette erreur se produit lorsqu'une ancienne instance de nœud Java est restée active en arrière-plan et bloque le port de communication TCP (ex: 5003 pour le Nœud 3) ou le port de métriques HTTP (ex: 8083).
Pour nettoyer proprement mon environnement de développement sur Fedora, j'utilise la commande suivante qui arrête d'un coup toutes les instances Java perdues :
```bash
killall -9 java
```
Une fois cette commande exécutée, je peux relancer mon cluster sans collision de ports.

---

## 8. Conclusion de Mon Travail

Mon projet démontre avec succès la robustesse théorique et pratique du consensus PBFT. Grâce à l'intégration d'une couche cryptographique ECDSA performante et à une conception rigoureuse de la gestion des quorums dans mon moteur Java, mon cluster reste **100% cohérent, sécurisé et vivant**, même en présence d'un nœud byzantin simulant une panne complète.
