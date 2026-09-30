# Terminal

Client SSH Android minimaliste (téléphones et tablettes) : une liste de serveurs, des onglets, un terminal. Rien d'autre.

## Fonctionnement

- **Accueil** : la liste des serveurs enregistrés. Un tap ouvre un onglet connecté.
- **Onglets** : `+` pour choisir un serveur. Clavier physique : `Ctrl+Shift+T`, `Ctrl+Shift+W`, `Ctrl+Tab`.
- **Authentification** : clé SSH (Ed25519 générée dans l'app ou importée), mot de passe (enregistré ou demandé à chaque fois), ou aucune (Tailscale SSH).
- **Empreintes** : confiance à la première connexion, alerte si l'empreinte change.
- **Sécurité** : tout (serveurs, mots de passe, clés, empreintes) est dans un seul fichier chiffré AES-256-GCM avec une clé de l'Android Keystore. Aucune sauvegarde cloud, aucune télémétrie, seule permission réseau : `INTERNET`.
- **Arrière-plan** : une notification garde les sessions ouvertes quand on change d'appli.
- **Mises à jour** : l'appli détecte les releases GitHub et s'installe par-dessus en gardant la configuration.

## Publier une version

Chaque push sur `main` compile l'APK signé et crée une release `v1.0.<n>` ; l'appli propose la mise à jour.
Le test sur émulateur (`Smoke test`) se lance à la main depuis l'onglet Actions.

## Secrets GitHub Actions

| Secret | Rôle |
|---|---|
| `SIGNING_KEYSTORE_B64` | keystore PKCS12 en base64 — **toujours le même**, sinon Android refuse la mise à jour |
| `SIGNING_STORE_PASSWORD` | mot de passe du keystore |
| `SIGNING_KEY_ALIAS` | alias de la clé |
| `UPDATE_TOKEN` | token GitHub *fine-grained*, lecture seule sur ce dépôt (`Contents: Read`), pour lire les releases du dépôt privé |

## Code tiers

Le moteur d'émulation (`com.termux.terminal`, `com.termux.view`) vient de
[termux-app](https://github.com/termux/termux-app), sous licence Apache 2.0 (voir `LICENSE-termux-terminal`),
adapté pour être piloté par SSH au lieu d'un processus local. SSH : [sshj](https://github.com/hierynomus/sshj) (Apache 2.0).
Police : Cascadia Mono (SIL OFL 1.1, voir `LICENSE-cascadia-font.txt`).
