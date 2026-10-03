# Terminal

Client SSH Android minimaliste (téléphones et tablettes) : une liste de serveurs, des onglets, un terminal. Plus un shell adb sur l'appareil lui-même.

## Fonctionnement

- **Accueil** : la liste des serveurs enregistrés. Un tap ouvre un onglet connecté.
- **Linux** (intégré, non supprimable) : un vrai Linux (Alpine) dans l'appli, comme Termux, sans root.
  À la première ouverture, il s'installe tout seul dans l'onglet : Node.js/npm, git, ripgrep, python3, adb,
  **Claude Code** (`claude`) et **Gemini CLI** (`gemini`). Ensuite `apk add …` et `npm install -g …` comme partout.
  - `proot` (paquet Termux, voir `tools/fetch-proot.sh`) est embarqué en bibliothèque native : Android interdit
    d'exécuter des fichiers téléchargés, son loader charge les binaires Linux lui-même.
  - `adb` y est déjà relié à l'appareil : même clé que l'entrée « Cet appareil », port trouvé par l'appli.
  - Appui long → « Réinstaller » efface l'environnement.
- **Cet appareil** (toujours en tête, non supprimable) : un `adb shell` sur l'appareil lui-même via le débogage sans fil
  (Android 11+), sans root ni PC. L'appli est son propre client adb (appairage, TLS, protocole shell v2).
  - Un assistant vérifie chaque prérequis (options développeur, Wi-Fi, débogage sans fil, appairage) et ouvre le bon écran des paramètres.
  - Le port d'appairage et le port de connexion sont trouvés tout seuls (mDNS) ; le code d'appairage se saisit dans une notification.
  - À la connexion, un test (`pm grant`) vérifie que adb peut agir sur le système ; s'il est bloqué (HyperOS/MIUI sans
    « Débogage USB (paramètres de sécurité) »), une alerte explique comment débloquer.
  - Ce test accorde `WRITE_SECURE_SETTINGS` à l'appli : elle réactive ensuite seule le débogage sans fil quand Android le coupe.
- **Onglets** : `+` pour choisir un serveur. Clavier physique : `Ctrl+Shift+T`, `Ctrl+Shift+W`, `Ctrl+Tab`.
- **Authentification** : clé SSH (Ed25519 générée dans l'app ou importée), mot de passe (enregistré ou demandé à chaque fois), ou aucune (Tailscale SSH).
- **Empreintes** : confiance à la première connexion, alerte si l'empreinte change.
- **Sécurité** : tout (serveurs, mots de passe, clés SSH et adb, empreintes) est dans un seul fichier chiffré AES-256-GCM avec une clé de l'Android Keystore. Aucune sauvegarde cloud, aucune télémétrie. Permissions réseau : `INTERNET` et `ACCESS_NETWORK_STATE` (savoir si le Wi-Fi est là pour adb).
- **Arrière-plan** : une notification garde les sessions ouvertes quand on change d'appli.
- **Mises à jour** : l'appli détecte les releases GitHub et s'installe par-dessus en gardant la configuration.

## Publier une version

Chaque push sur `main` compile l'APK signé et crée une release `v1.0.<n>` ; l'appli propose la mise à jour.
En parallèle, `Smoke test` lance l'appli sur un émulateur avec une vraie connexion SSH et une vraie connexion adb
(l'adbd de l'émulateur, exposé en TCP, remplace le débogage sans fil).

## Secrets GitHub Actions

| Secret | Rôle |
|---|---|
| `SIGNING_KEYSTORE_B64` | keystore PKCS12 en base64 — **toujours le même**, sinon Android refuse la mise à jour |
| `SIGNING_STORE_PASSWORD` | mot de passe du keystore |
| `SIGNING_KEY_ALIAS` | alias de la clé |

## Code tiers

Le moteur d'émulation (`com.termux.terminal`, `com.termux.view`) vient de
[termux-app](https://github.com/termux/termux-app), sous licence Apache 2.0 (voir `LICENSE-termux-terminal`),
adapté pour être piloté par SSH au lieu d'un processus local. SSH : [sshj](https://github.com/hierynomus/sshj) (Apache 2.0).
Linux : [proot](https://github.com/termux/proot) (GPL-2.0, binaire du paquet Termux, voir `LICENSE-proot`),
[talloc](https://talloc.samba.org) (LGPL-3.0), [libandroid-shmem](https://github.com/termux/libandroid-shmem) (BSD-3-Clause) ;
Alpine Linux est téléchargé depuis ses miroirs officiels (somme SHA-256 vérifiée).
adb : [libadb-android](https://github.com/MuntashirAkon/libadb-android) (Apache 2.0, au choix avec GPL-3.0),
qui dépend de [spake2-java](https://github.com/MuntashirAkon/spake2-java) (LGPL-3.0) et de [Conscrypt](https://github.com/google/conscrypt) (Apache 2.0).
Police : Cascadia Mono (SIL OFL 1.1, voir `LICENSE-cascadia-font.txt`).
