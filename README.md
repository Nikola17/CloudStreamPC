# CloudStreamPC

**CloudStream pour Windows.** Une application de bureau qui reprend l'interface et les fonctions de
l'application [CloudStream](https://github.com/recloudstream/cloudstream) (version TV) et charge
**les mêmes extensions (`.cs3`)**, plus les **comptes IPTV** (Xtream Codes / M3U) et les
**addons Stremio**.

## Installation (Windows 10/11, 64 bits)

1. Ouvrez la page [Releases](../../releases/latest).
2. Téléchargez `CloudStreamPC-x.y.z-setup.exe` et lancez-le.
   Version sans installation : `CloudStreamPC-x.y.z-portable.zip`, à décompresser, puis lancez `CloudStreamPC.exe`.
3. Si Windows affiche « Windows a protégé votre ordinateur » (l'application n'est pas signée) :
   cliquez sur **Informations complémentaires** puis **Exécuter quand même**.

Java et le lecteur vidéo [mpv](https://mpv.io) sont inclus : rien d'autre à installer.
Au premier lancement, un assistant propose d'installer les extensions CloudStream FR, d'ajouter un
compte IPTV et le catalogue Stremio.

## Fonctionnalités

**Accueil** (comme CloudStream TV) : grande bannière, « Continuer à regarder », rangées par catégorie
avec affiches (badge qualité HD/4K et note), flèche « Voir tout », bouton filtre en bas à droite pour
choisir le fournisseur (filtres Films / Séries / Animés / TV…), bouton aléatoire et horloge en option.

**Lecture** : la meilleure qualité se lance directement, sans choisir (le bouton « Sources » de la fiche
permet de choisir à la main). Si une source ne marche pas, l'application passe à la suivante.
Reprise à la seconde près, épisode coché « vu » à 90 %, épisode suivant automatique avec compte à
rebours, sous-titres, langues audio/sous-titres préférées, vitesse de lecture.

**Fiches** : saisons, épisodes, VF/VOSTFR, progression, distribution, recommandations, favoris,
téléchargement d'un film ou d'un épisode.

**Recherche** dans tous les fournisseurs à la fois, avec filtres et historique de recherche.

**Téléchargements** : vidéos directes et flux HLS (y compris chiffrés AES-128), pause / reprise,
téléchargements simultanés, lecture hors connexion.

**IPTV** (Paramètres → Comptes et Sécurité → Comptes IPTV) : abonnement **Xtream Codes** (adresse du
serveur + identifiant + mot de passe) ou **playlist M3U**. Chaque compte devient un fournisseur avec ses
chaînes TV (guide des programmes), films et séries, rangés par catégorie, et recherchables.

**Stremio** (Paramètres → Extensions → Addons Stremio) : ajout d'addons par leur lien `manifest.json`
(catalogues sur l'accueil, recherche, fiches, flux, sous-titres). Préréglages : Cinemeta, OpenSubtitles.
Quand un titre n'a pas de source, un bouton le cherche dans les autres fournisseurs.

**Paramètres** (mêmes rubriques que l'application Android) :
- *Général* : dossier et nombre de téléchargements, **DNS over HTTPS** (Cloudflare, Google, AdGuard,
  Quad9…) contre les blocages des fournisseurs d'accès, liens.
- *Lecteur* : qualité préférée, lecteur (mpv inclus, VLC, autre), lecture directe, épisode suivant,
  progression, plein écran, vitesse, sous-titres, langues, cache.
- *Fournisseurs* : langues des extensions, média préféré, badges VF/VOSTFR, NSFW, test de toutes les extensions.
- *Disposition* : couleur principale, thème (noir, sombre, gris), taille des affiches, position du
  titre, badges, bannière, distribution, bouton aléatoire, horloge, historique, confirmation avant de quitter.
- *Mises à jour et Sauvegarde* : vérification des mises à jour, sauvegarde / restauration (manuelle
  ou automatique), mise à jour des extensions, journaux, configuration initiale.
- *Comptes et Sécurité* : **profils** (favoris, historique et progression séparés, code par profil,
  « Qui regarde ? » au démarrage), comptes IPTV, addons Stremio, verrouillage de l'application par code.
- *Extensions* : dépôts et extensions CloudStream (installation, mise à jour, désinstallation) et addons Stremio.

Raccourcis : `Échap`, `Alt+←` ou le bouton « précédent » de la souris pour revenir en arrière.
Dans mpv : `Espace` pause, `F` plein écran, `←`/`→` ±5 s, `↑`/`↓` ±1 min, `J` sous-titres, `#` piste audio, `Q` quitter.

Les données sont dans `%APPDATA%\CloudStreamPC` ; les téléchargements dans `Vidéos\CloudStreamPC`.
En cas de problème : Paramètres → Mises à jour et Sauvegarde → Afficher les journaux.

## Limites connues

- Les flux **torrent** (certaines extensions, addons Stremio comme Torrentio) ne sont pas lisibles.
- Pas de synchronisation AniList / MyAnimeList / Simkl ni de Chromecast.
- Les extensions qui utilisent des fonctions propres à Android (réglages internes, ressources) peuvent
  ne pas fonctionner entièrement.
- Le DNS over HTTPS s'applique aux extensions, pas au lecteur mpv.

## Comment ça marche

- L'API des extensions est la bibliothèque officielle de CloudStream (module `library`, compilé pour la
  JVM), récupérée via JitPack.
- Une extension `.cs3` contient du bytecode Android (DEX). À l'installation, il est converti une fois pour
  toutes en classes Java avec [dex2jar](https://github.com/ThexXTURBOXx/dex2jar), puis chargé comme sur
  Android (`manifest.json` → `pluginClassName` → `load()`).
- L'IPTV et Stremio sont des fournisseurs intégrés qui utilisent la même API que les extensions.
- La position de lecture est lue en direct dans mpv par son IPC (pipe nommé sous Windows).

## Compiler soi-même

Prérequis : JDK 21.

```bash
./gradlew run                    # lancer l'application
./gradlew test                   # tests (IPTV, téléchargements HLS, mpv, dépôts)
./gradlew test -Dtest.network=true -Dtest.search=true   # tests réseau (extensions réelles, Cinemeta)
./gradlew packageExe packageMsi  # installateurs (à lancer sous Windows)
```

Chaque envoi sur `main` compile les installateurs Windows avec GitHub Actions et les publie dans une release.

## Licence

GPL-3.0, comme CloudStream.
