# CloudStreamPC

**CloudStream pour Windows.** Une application de bureau qui reprend l'interface et les fonctions de
l'application [CloudStream](https://github.com/recloudstream/cloudstream) (version TV) et charge
**les mêmes extensions (`.cs3`)**, plus les **comptes IPTV** (Xtream Codes / M3U) et les
**addons Stremio**.

![Accueil de CloudStreamPC](docs/screenshots/home.jpg)

## Installation (Windows 10/11, 64 bits)

1. Ouvrez la page [Releases](../../releases/latest).
2. Téléchargez `CloudStreamPC-x.y.z-setup.exe` et lancez-le.
   Version sans installation : `CloudStreamPC-x.y.z-portable.zip`, à décompresser, puis lancez `CloudStreamPC.exe`.
3. Si Windows affiche « Windows a protégé votre ordinateur » (l'application n'est pas signée) :
   cliquez sur **Informations complémentaires** puis **Exécuter quand même**.

Java et le lecteur vidéo [mpv](https://mpv.io) sont inclus : rien d'autre à installer.
Au premier lancement, un assistant propose d'installer les extensions CloudStream FR, d'ajouter un
compte IPTV et le catalogue Stremio.

## Captures d'écran

| | |
|---|---|
| ![Recommandations](docs/screenshots/recos.jpg) | ![Top 10](docs/screenshots/top10.jpg) |
| **Recommandations** calculées à partir de ce que vous regardez | **Top 10** et rangées par catégorie |
| ![Fiche d'une série](docs/screenshots/details.jpg) | ![Recherche](docs/screenshots/search.jpg) |
| **Fiche d'une série** : saisons, épisodes, téléchargement | **Recherche** dans toutes les extensions |
| ![Paramètres](docs/screenshots/settings.jpg) | ![Extensions](docs/screenshots/extensions.jpg) |
| **Paramètres** organisés comme l'application Android | **Extensions** CloudStream et addons Stremio |

![Guide TV](docs/screenshots/tvguide.jpg)
**Guide TV** des comptes IPTV : programme en cours, heure actuelle, lecture d'un clic.

## Fonctionnalités

**Accueil** façon plateforme VOD : grande bannière avec le logo officiel du titre quand il existe, zoom
lent et lecture directe ; onglets Accueil / Films / Séries / Animés / TV ; « Continuer à regarder » en
vignettes paysage (un clic reprend la lecture) ; Top 10 ; rangées avec défilement au survol et « Tout voir » ;
affiches avec badge qualité et note ; bouton filtre en bas à droite pour choisir le fournisseur.
Typographie : Outfit pour les titres et Inter pour le texte (polices libres, SIL OFL).

**Recommandations** sans compte (ni Simkl ni Trakt), calculées sur le PC à partir de ce que vous
regardez : « Recommandé pour vous » et « Parce que vous avez regardé… ». Un titre fini compte plus qu'un
titre abandonné, les favoris comptent aussi, et un titre suggéré par plusieurs de vos films passe devant.
Quand une extension ne propose pas de titres similaires, l'application cherche le même titre dans vos autres
fournisseurs. La croix au survol d'une affiche écarte un titre. Séparées par profil, réglables dans Disposition.

**Lecture** : la meilleure qualité se lance directement, sans choisir (le bouton « Sources » de la fiche
permet de choisir à la main). Si une source ne marche pas, l'application passe à la suivante.
Reprise à la seconde près, épisode coché « vu » à 90 %, sous-titres, langues audio/sous-titres préférées,
vitesse de lecture.

**Lecteur** : mpv avec une interface moderne ([uosc](https://github.com/tomasklaen/uosc)) : barre de
progression avec chapitres, menus des sous-titres, de la piste audio et de la qualité, vitesse, plein écran,
bouton « +85 s ». Par-dessus, les boutons de CloudStreamPC :
- **Passer l'intro / le récap** : pour les animés, temps de la base [AniSkip](https://aniskip.com)
  (comme CloudStream) ; pour les autres vidéos, quand leurs chapitres indiquent l'intro ou le générique.
  Peut aussi passer l'intro automatiquement.
- **Épisode suivant** : apparaît au générique de fin (compte à rebours de 10 s quand le générique est
  connu, sinon à la fin de l'épisode). L'épisode suivant se charge **dans la même fenêtre**, sans quitter le
  plein écran, avec sa meilleure source. Touche `N` ou bouton de la barre de contrôle à tout moment.

**Guide TV** (bouton « Guide TV » de l'accueil) : grille des programmes des comptes IPTV (guide XMLTV du
fournisseur, mis en cache 12 h), filtre par catégorie, ligne de l'heure actuelle, progression du programme
en cours ; un clic lance la chaîne. La fiche d'une chaîne affiche « En ce moment » et « Ensuite ».

**Nouveaux épisodes** : les séries en favoris et celles regardées ces 60 derniers jours sont vérifiées au
démarrage puis toutes les 6 heures. Les sorties apparaissent dans une rangée « Nouveaux épisodes » de
l'accueil, dans la Bibliothèque (pastille sur son icône) et en notification Windows.

**Bandes-annonces** : après quelques secondes sur un titre, la bannière de l'accueil lance sa bande-annonce
sans le son, comme sur les plateformes ; bouton « Bande-annonce » (avec le son) sur les fiches. La vidéo
vient de l'extension ou du catalogue Cinemeta, lue depuis YouTube grâce à [yt-dlp](https://github.com/yt-dlp/yt-dlp).

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
- *Général* : dossier et nombre de téléchargements, nouveaux épisodes et notifications, **DNS over HTTPS**
  (Cloudflare, Google, AdGuard, Quad9…) contre les blocages des fournisseurs d'accès, liens.
- *Lecteur* : qualité préférée, lecteur (mpv inclus, VLC, autre), lecture directe, épisode suivant,
  progression, plein écran, vitesse, interface du lecteur, « Passer l'intro » (manuel ou automatique),
  bouton « +85 s », sous-titres, langues, cache.
- *Fournisseurs* : langues des extensions, média préféré, badges VF/VOSTFR, NSFW, test de toutes les extensions.
- *Disposition* : couleur principale, thème (noir, sombre, gris), taille des affiches, position du
  titre, badges, bannière, bandes-annonces, distribution, bouton aléatoire, horloge, historique, confirmation avant de quitter.
- *Mises à jour et Sauvegarde* : vérification des mises à jour, sauvegarde / restauration (manuelle
  ou automatique), mise à jour des extensions, journaux, configuration initiale.
- *Comptes et Sécurité* : **profils** (favoris, historique et progression séparés, code par profil,
  « Qui regarde ? » au démarrage), comptes IPTV, addons Stremio, verrouillage de l'application par code.
- *Extensions* : dépôts et extensions CloudStream (installation, mise à jour, désinstallation) et addons Stremio.

Raccourcis : `Échap`, `Alt+←` ou le bouton « précédent » de la souris pour revenir en arrière.
Dans mpv : `Espace` pause, `F` plein écran, `←`/`→` ±5 s, `↑`/`↓` ±1 min, `J` sous-titres, `#` piste audio,
`Entrée` « Passer l'intro » / « Épisode suivant » quand le bouton est affiché, `N` épisode suivant, `Q` quitter.

Les données sont dans `%APPDATA%\CloudStreamPC` ; les téléchargements dans `Vidéos\CloudStreamPC`.
En cas de problème : Paramètres → Mises à jour et Sauvegarde → Afficher les journaux.

## Signature de l'application

Les versions publiées peuvent être signées (Authenticode SHA-256 horodaté) : installateur `.exe`, `.msi`
et exécutable de la version portable. Il faut un certificat de signature de code au nom de son
propriétaire, délivré par une autorité reconnue par Windows (vérification d'identité obligatoire).
Une fois le certificat obtenu, il suffit d'ajouter des secrets au dépôt GitHub (Settings → Secrets and
variables → Actions) ; la compilation signe alors automatiquement :

- **Certificat dans Azure Key Vault** (cas général : depuis 2023 les clés doivent rester dans un module
  matériel) : `AZURE_KEY_VAULT_URI`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`, `AZURE_TENANT_ID`, `AZURE_CERT_NAME`.
- **Fichier .pfx** : `WINDOWS_CERTIFICATE_PFX` (contenu du .pfx en base64) et `WINDOWS_CERTIFICATE_PASSWORD`.

Sans certificat, les fichiers ne sont pas signés ; leurs empreintes SHA-256 sont publiées dans
`SHA256SUMS.txt` avec chaque version. Le lancement manuel de la compilation propose un test de la chaîne
de signature avec un certificat jetable (sans publier de version).

## Limites connues

- Les flux **torrent** (certaines extensions, addons Stremio comme Torrentio) ne sont pas lisibles.
- Pas de synchronisation AniList / MyAnimeList / Simkl ni de Chromecast.
- Les extensions qui utilisent des fonctions propres à Android (réglages internes, ressources) peuvent
  ne pas fonctionner entièrement.
- Le DNS over HTTPS s'applique aux extensions, pas au lecteur mpv.
- Le guide TV dépend du guide XMLTV fourni par le compte IPTV ; les chaînes des extensions n'en ont pas.
- « Passer l'intro » n'est automatique que pour les animés présents dans AniSkip et les vidéos à chapitres ;
  ailleurs, le bouton « +85 s » de la barre de contrôle avance d'un générique type.
- Les bandes-annonces passent par YouTube : si YouTube change, une mise à jour de yt-dlp (incluse dans
  la version suivante) peut être nécessaire.

## Comment ça marche

- L'API des extensions est la bibliothèque officielle de CloudStream (module `library`, compilé pour la
  JVM), récupérée via JitPack.
- Une extension `.cs3` contient du bytecode Android (DEX). À l'installation, il est converti une fois pour
  toutes en classes Java avec [dex2jar](https://github.com/ThexXTURBOXx/dex2jar), puis chargé comme sur
  Android (`manifest.json` → `pluginClassName` → `load()`).
- L'IPTV et Stremio sont des fournisseurs intégrés qui utilisent la même API que les extensions.
- La position de lecture est lue en direct dans mpv par son IPC (pipe nommé sous Windows) ; la même
  connexion sert au script du lecteur (`app-resources/common/mpv/scripts/cloudstreampc.lua`) pour
  demander l'épisode suivant, que l'application charge dans la fenêtre ouverte.
- Les bandes-annonces de l'accueil sont décodées par mpv en images brutes, affichées dans l'interface :
  le texte de la bannière reste par-dessus.

## Composants inclus

[mpv](https://mpv.io) (GPL), [uosc](https://github.com/tomasklaen/uosc) 5.9.2 (LGPL-2.1, non modifié),
[yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense), polices Outfit et Inter (SIL OFL), bibliothèque
CloudStream (GPL-3.0).

## Compiler soi-même

Prérequis : JDK 21.

```bash
./gradlew run                    # lancer l'application
./gradlew test                   # tests (IPTV, guide TV, téléchargements HLS, mpv, AniSkip, dépôts…)
./gradlew test -Dtest.network=true -Dtest.search=true   # tests réseau (extensions réelles, Cinemeta)
./gradlew packageExe packageMsi  # installateurs (à lancer sous Windows)
```

Chaque envoi sur `main` compile les installateurs Windows avec GitHub Actions et les publie dans une release.

## Licence

GPL-3.0, comme CloudStream.
