# PodHub

Appli Android perso pour centraliser ses podcasts : flux RSS, Patreon, YouTube (converti en MP3) et od3on.

## Sources

| Source | Fonctionnement |
|---|---|
| **Flux RSS** | URL du flux, identifiants HTTP optionnels pour les flux privés. |
| **Patreon** | Lien RSS privé fourni par Patreon (page du créateur → Audio / ⋯ → « Obtenir le lien RSS »). |
| **YouTube** | Chaîne (`@nom`, `/channel/UC…`), playlist ou vidéo. Les chaînes/playlists sont suivies via le flux Atom officiel ; chaque vidéo est convertie en MP3 sur le téléphone (yt-dlp + ffmpeg). « Partager » depuis l'appli YouTube vers PodHub fonctionne aussi. |
| **Od3on** | Pas de RSS ni d'API publics. Navigateur intégré : on se connecte, on ouvre un post, on lance la lecture, puis « Récupérer l'audio » capte le fichier (ou le flux HLS) et le télécharge avec la session. |

## Fonctions

- Onglets Nouveautés / Abonnements / Téléchargés, mini-lecteur
- Lecture en arrière-plan (notification, écran de verrouillage, casque), reprise de position, vitesse 0,8×–2×, ±15/30 s
- Actualisation automatique toutes les 4 h, téléchargement auto par abonnement
- Menu ⋯ → « Mettre à jour yt-dlp » quand YouTube casse les téléchargements

## Compiler

JDK 17 et Android SDK (platform 35, build-tools 35) requis.

```
gradlew assembleDebug
```

APK : `app/build/outputs/apk/debug/app-debug.apk`

## Limites

- Télécharger depuis YouTube est contraire à ses CGU : usage personnel uniquement.
- La capture od3on dépend de la façon dont le site sert ses médias ; les lecteurs protégés par DRM ne sont pas récupérables.
- Les identifiants des flux privés sont stockés en clair dans la base locale de l'appli.
