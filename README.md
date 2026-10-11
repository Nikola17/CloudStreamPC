# CloudStreamPC

**CloudStream pour Windows.** Une application de bureau qui reprend le fonctionnement de
l'application Android [CloudStream](https://github.com/recloudstream/cloudstream) et charge
**les mêmes extensions (`.cs3`)** et **les mêmes dépôts**.

## Installation (Windows 10/11, 64 bits)

1. Ouvrez la page [Releases](../../releases/latest).
2. Téléchargez `CloudStreamPC-x.y.z-setup.exe` et lancez-le.
   Version sans installation : `CloudStreamPC-x.y.z-portable.zip`, à décompresser, puis lancez `CloudStreamPC.exe`.
3. Si Windows affiche « Windows a protégé votre ordinateur » (l'application n'est pas signée) :
   cliquez sur **Informations complémentaires** puis **Exécuter quand même**.

Java et le lecteur vidéo [mpv](https://mpv.io) sont inclus : rien d'autre à installer.

## Utilisation

- **Extensions** : le dépôt « CloudStream FR » est proposé au premier lancement, cliquez sur
  **Tout installer**. Vous pouvez ajouter n'importe quel dépôt CloudStream (lien `repo.json` ou
  `cloudstreamrepo://…`), exactement comme sur Android. Les extensions se mettent à jour au démarrage.
- **Accueil** : choisissez l'extension en haut à gauche pour voir ses catégories ; « Voir tout » ouvre
  la catégorie complète.
- **Recherche** : cherche dans toutes les extensions installées en même temps, avec filtres
  Films / Séries / Animés / Direct.
- **Fiche** : saisons, épisodes, VF/VOSTFR pour les animés, recommandations, favoris. Le bouton
  **Lire** ouvre la liste des sources ; choisissez-en une (ou « Lire la meilleure ») et la vidéo
  s'ouvre dans mpv avec les sous-titres disponibles.
- **Bibliothèque** : favoris et historique (« Reprendre » propose le dernier épisode lancé).
- **Paramètres** : choix du lecteur (mpv inclus, VLC ou autre), mise à jour automatique des
  extensions, accès aux données et aux journaux.

Raccourcis : `Échap`, `Alt+←` ou le bouton « précédent » de la souris pour revenir en arrière.

Raccourcis de mpv pendant la lecture : `Espace` pause, `F` plein écran, `←`/`→` ±5 s,
`↑`/`↓` ±1 min, `J` change de sous-titres, `#` change de piste audio, `Q` quitte.

Les données (favoris, historique, extensions, journaux) sont dans `%APPDATA%\CloudStreamPC`.
En cas de problème, envoyez le fichier `logs\cloudstreampc.log` de ce dossier.

## Comment ça marche

- L'API des extensions est la bibliothèque officielle de CloudStream (module `library`, compilé
  pour la JVM), récupérée via JitPack.
- Une extension `.cs3` contient du bytecode Android (DEX). À l'installation, il est converti une
  fois pour toutes en classes Java avec [dex2jar](https://github.com/ThexXTURBOXx/dex2jar), puis
  chargé comme sur Android (`manifest.json` → `pluginClassName` → `load()`).
- Les extensions qui utilisent des fonctions propres à Android (paramètres d'extension, ressources)
  peuvent ne pas fonctionner entièrement.

## Compiler soi-même

Prérequis : JDK 21.

```bash
./gradlew run                    # lancer l'application
./gradlew test                   # tests
./gradlew test -Dtest.network=true -Dtest.search=true   # test réel des extensions (réseau)
./gradlew packageExe packageMsi  # installateurs (à lancer sous Windows)
```

Chaque envoi sur `main` compile les installateurs Windows avec GitHub Actions et les publie dans
une release.

## Licence

GPL-3.0, comme CloudStream.
