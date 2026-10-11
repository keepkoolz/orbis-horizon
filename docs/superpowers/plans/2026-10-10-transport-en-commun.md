# Transport en commun : plan d'implémentation

> **Pour les agents :** sous-compétence requise, superpowers:subagent-driven-development (recommandée) ou superpowers:executing-plans, pour exécuter ce plan tâche par tâche. Les étapes utilisent des cases à cocher (`- [ ]`).

**But :** des montgolfières sans pilote transportent les joueurs d'une station à une autre, à la demande, choisie auprès d'un chef de gare.

**Architecture :** on réutilise le vol sans pilote existant (`BalloonFlight` avec `driven`, `tickDriven`, parties, vue multijoueur, lumières, passagers `follow`). Un champ `transit` dans `BalloonFlight` fait demander la position cible à `TransitAutopilot`. Le réseau, les files, le PNJ et les fenêtres vivent dans de nouvelles classes. `BalloonManager` ne reçoit que des points d'appel.

**Pile :** Java 21 (compilé par `scripts/build-plugin.sh` contre `HytaleServer.jar` 0.6.8), assets JSON du mod, `scripts/prefab_to_model.py` (Python et Pillow).

**Spec :** `docs/superpowers/specs/2026-10-10-transport-en-commun-design.md`. L'exécutant lit la spec et ce plan.

## Contraintes globales

- Branche `feature/transport-en-commun`, API 0.6.8 (`World.getBlockType`, `WorldChunk.setBlock`). Ne rien porter vers 0.7 ici.
- Tout message de chat passe par `Texts.t("<clé>")`, toute description de commande par `Texts.cmd("<clé>")`, avec la clé ajoutée dans `Server/Languages/en-US/server.lang` et `fr-FR/server.lang`. Jamais de `Message.raw` en français.
- Commentaires Java en anglais, comme le code existant. Messages de commit en français, terminés par `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Identifiants : types `station` et `balloon_public`, blocs `Hotair_Balloon_Public_Burner` et `Balloon_Station_Marker`, caisse `Balloon_Station_Crate`, modèle `Hotair_Balloon_Public` (`_Off`, `_Boost`, parties `_Part<k>`), rôle `Station_Master`, fichier `stations.json`.
- Valeurs de la spec, à mettre en constantes nommées dans `TransitConstants` (tâche 3) : rayon des messages 20 blocs, compte à rebours 60 s, message toutes les 10 s, file de 5 destinations au plus, apparition 15 blocs au-dessus du plateau, quai libéré à 20 blocs au-dessus du plateau, disparition 20 blocs au-dessus du point de dépose, croisière 30 blocs au-dessus du plus haut des deux plateaux, attente des chunks 10 s, secours après 30 s sans progrès, point d'attente à 30 blocs du plateau avec un écart de 6 blocs par arrivante, réservation du PNJ levée à 8 blocs ou après 60 s, nom de 3 à 24 caractères, stations à 64 blocs au moins l'une de l'autre, retrait dans 48 blocs, 12 passagers.
- Vitesses : celles des montgolfières actuelles, lues comme `defaultDescentSpeed` le fait aujourd'hui (vitesse de vol du joueur divisée par 2), horizontale et verticale.
- En vol de transport, une case au-dessus de la limite de construction compte comme libre. Les poses en blocs vérifient toujours la limite.
- Vérification de chaque tâche : `scripts/build-plugin.sh` sans erreur, `python3 scripts/prefab_to_model.py --check --balloon <profil>` à 0 erreur pour chaque profil touché, `scripts/deploy.sh --dry-run` réussi, et `scripts/run-tests.sh` (tâche 3) au vert.
- Les classes compilées de `dist/classes/` sont commitées avec leur source.

## Points d'attention de relecture

1. Deux joueurs qui ouvrent le chef de gare au même tick : un seul obtient la fenêtre, l'autre reçoit `transit.master.busy`. Testé dans `TransitTests.reservationIsExclusive` (tâche 5).
2. Un nom de station avec des espaces en tête ou en fin, ou ne différant que par la casse d'un nom existant : il est rogné et refusé comme doublon (comparaison sans casse). Testé dans `TransitTests.nameRules` (tâche 3).
3. Une destination supprimée alors qu'une montgolfière y va, et la station de départ supprimée aussi : la montgolfière va à la station la plus proche, ou se pose au sol s'il n'en reste aucune. Testé dans `TransitTests.rerouteTarget` (tâche 9).
4. Un joueur dans la nacelle au moment exact où la montgolfière d'approche se repose en blocs : il ne doit pas être enfermé dans un bloc. La pose ignore les joueurs, mais la nacelle est creuse au-dessus du plancher. Vérifié en jeu, test 6 de la tâche 11.
5. Deux arrivantes en attente au même quai : points d'attente distincts et ordre d'arrivée respecté. Testé dans `TransitTests.holdingPoints` (tâche 8).

---

### Tâche 1 : essai du PNJ, des fenêtres et du grand titre (jetable)

But : répondre aux quatre questions de la section « Essai préalable » de la spec. Le code de l'essai n'est pas gardé.

**Fichiers :**
- Créer sur une branche jetable `spike/transport-ui` (depuis `feature/transport-en-commun`) : `plugin/src/main/java/com/keepkoolz/orbishorizon/SpikeCommand.java`, une page `Common/UI/Custom/Pages/Spike_Destinations.ui`, un rôle `Server/NPC/Roles/Spike_Station_Master.json`.
- Créer sur `feature/transport-en-commun` : `docs/superpowers/notes/2026-10-10-essai-transport-ui.md` (le rapport).

- [ ] **Étape 1 : lire le bytecode** avec `javap -p -c` sur `PageManager`, `CustomUIPage`, `InteractiveCustomUIPage`, `BasicCustomUIPage`, `OpenCustomUIInteraction`, `EventTitleUtil`, `UICommandBuilder`, `UIEventBuilder`, `BuilderActionOpenBarterShop`, et lire dans `Assets.zip` un `.ui` du jeu avec une liste et un champ de texte (`Common/UI/Custom/Pages/`), ainsi que le rôle `Kweebec_Merchant`. Noter les signatures dans le rapport.
- [ ] **Étape 2 : écrire `/orbishorizon spike`** avec quatre sous-commandes : `npc` (fait apparaître un PNJ du rôle d'essai devant le joueur, `Frozen` et `Invulnerable`, puis essaie la voie 1 : composant `Interactions` avec une racine d'interaction du mod qui ouvre la page), `page` (ouvre la page avec 3 boutons générés et renvoie au chat le bouton cliqué), `name` (ouvre une page avec un champ de texte et renvoie la valeur saisie), `title` (affiche `EventTitleUtil.showEventTitleToPlayer` avec un titre et un sous-titre).
- [ ] **Étape 3 : compiler et déployer** : `scripts/build-plugin.sh` puis `scripts/deploy.sh`.
- [ ] **Étape 4 : faire tester par l'utilisateur** les quatre sous-commandes en jeu. Si la voie 1 du PNJ échoue, essayer la voie 2 (action du rôle sur le modèle de `OpenBarterShop`) avant de conclure.
- [ ] **Étape 5 : écrire le rapport** : pour chaque question, oui ou non, la voie retenue pour le PNJ, les signatures exactes, le format `.ui` qui marche, et ce qui reste douteux.
- [ ] **Étape 6 : revenir sur `feature/transport-en-commun`**, commiter le rapport seul (`Essai du chef de gare et des fenêtres : rapport`) et supprimer la branche `spike/transport-ui`.
- [ ] **Étape 7 : point d'arrêt.** Si une page ou le PNJ est impossible, s'arrêter et demander à l'utilisateur de choisir le repli (choix par le chat). Sinon, les tâches 5 et 7 suivent la voie notée dans le rapport.

### Tâche 2 : prefabs, blocs du mod, types et modèle de vol

**Fichiers :**
- Modifier : `Server/Prefabs/Balloon_Public_Transport.prefab.json`, `Server/Prefabs/Balloon_Landing_zone.prefab.json`
- Créer : `Server/Item/Items/Containers/Vehicles/Hotair_Balloon_Public_Burner.json`, `Server/Item/Items/Containers/Vehicles/Balloon_Station_Marker.json`, `Server/Models/Vehicles/Hotair_Balloon_Public.json` et `_Off.json`, `_Boost.json` (écrits par le générateur), `Common/Items/Vehicles/Hotair_Balloon_Public*.blockymodel` et la texture
- Modifier : `plugin/src/main/java/com/keepkoolz/orbishorizon/Deployables.java`, `scripts/prefab_to_model.py`, les deux `server.lang`

**Interfaces :**
- Produit : `Deployables.STATION_KIND = "station"`, `Deployables.STATION` (`Kind`, repère `Balloon_Station_Marker`, `requireBurner` faux), `Deployables.PUBLIC_KIND = "balloon_public"`, `Deployables.BALLOON_PUBLIC` (`Kind` avec un `BalloonSpec` dont `crateItemId` est `null`), et les constantes du repère du prefab de la station : `STATION_PAD_MIN` et `STATION_PAD_MAX` (plateau de 5 x 5, cases du dessus), `STATION_DOCK_ORIGIN` (origine où la montgolfière se pose en blocs), `STATION_BOARDING_POINTS` (`float[][]`, au moins 6 points de dépose sur la plateforme de pré-embarquement), `STATION_MASTER_POS` (case du PNJ dans la hutte), `STATION_GRASS_Y = -1`, `PUBLIC_PIVOT`, `PUBLIC_NACELLE_MIN` et `PUBLIC_NACELLE_MAX`.
- `BALLOON_PUBLIC` est dans `ALL`, pas dans `BALLOONS`.

- [ ] **Étape 1 : préparer le prefab de la montgolfière** avec un script ponctuel hors du dépôt : décaler x de -4 pour centrer la nacelle sur x = 0 (vérifier que la nacelle va de -2 à 2 en x et en z), retirer les clés `components` vides des 3 tabourets, remplacer `Metal_Goblin_Iron_Pipe_Large_Glowy_Mouthpiece` par `Hotair_Balloon_Public_Burner` (rotation gardée), décaler les entrées `fluids` de la même façon. Format d'origine gardé (indentation, ordre des champs, retour à la ligne final).
- [ ] **Étape 2 : préparer le prefab de la station** : un des 6 `Furniture_Village_Brazier` (celui le plus proche du plateau) devient `Balloon_Station_Marker`. Relever en blocs du prefab, dans le rapport de commit, les valeurs des constantes de l'Interface ci-dessus.
- [ ] **Étape 3 : créer les deux blocs.** `Hotair_Balloon_Public_Burner` : copie de `Metal_Goblin_Iron_Pipe_Large_Glowy_Mouthpiece.json` du jeu (aplatie, sans `Parent`), avec un état `On` dont la lumière et les particules reprennent celles de l'état `On` de `Hotair_Balloon_Burner.json`, sans `Bench`, sans `BlockEntity`, sans `Interactions`, sans recette. `Balloon_Station_Marker` : copie aplatie de `Furniture_Village_Brazier.json`, sans recette. Noms et descriptions dans les deux langues.
- [ ] **Étape 4 : ajouter les types dans `Deployables`** avec les constantes de l'Interface. `BalloonSpec` de `BALLOON_PUBLIC` : modèle `Hotair_Balloon_Public`, systèmes de flamme de la grande montgolfière.
- [ ] **Étape 5 : ajouter le profil `public` au générateur** (prefab `Balloon_Public_Transport.prefab.json`, modèle `Hotair_Balloon_Public`, pivot `PUBLIC_PIVOT`, 12 sièges attendus, hitbox calculée sur le prefab, pas de chaîne exigée, brûleur `Hotair_Balloon_Public_Burner` dans `MODEL_ALIASES` vers le modèle du tuyau gobelin, `write_model_jsons` comme pour la petite). Le contrôle « chaîne sous le brûleur » est sauté pour ce profil.
- [ ] **Étape 6 : générer et vérifier.** `python3 scripts/prefab_to_model.py --check --balloon public` donne 0 erreur et 0 avertissement. Générer, puis `--check` des quatre autres profils sans changement de leurs fichiers (`git status` ne montre aucun autre modèle modifié).
- [ ] **Étape 7 : compiler** (`scripts/build-plugin.sh`) et `scripts/deploy.sh --dry-run`. Le journal de démarrage attendu (à vérifier plus tard en jeu) : « Montgolfière « balloon_public » : N case(s), 12 siège(s) ».
- [ ] **Étape 8 : commiter** (`Transport en commun : prefabs, blocs du mod, types et modèle de vol`).

### Tâche 3 : constantes, réseau des stations et tests de logique pure

**Fichiers :**
- Créer : `plugin/src/main/java/com/keepkoolz/orbishorizon/TransitConstants.java`, `TransitNetwork.java`, `plugin/src/test/java/com/keepkoolz/orbishorizon/TransitTests.java`, `scripts/run-tests.sh`
- Modifier : `OrbisHorizonPlugin.java` (chargement au démarrage)

**Interfaces :**
- Produit : `TransitNetwork.Station` (champs : `String name`, `String world`, `Vector3i origin`, `Rotation rotation`, `UUID masterUuid`, `UUID owner`, `List<String> queue`), `static TransitNetwork get()`, `List<Station> stations(String world)`, `Station byName(String world, String name)` (sans casse), `Station at(String world, Vector3i origin)`, `Station nearest(String world, Vector3d pos, double maxDist)`, `void add(Station s)`, `void remove(Station s)`, `void save()` (atomique, `.tmp` puis renommage, comme `BalloonRegistry`), `void load(Path dataDir)`.
- Produit : `static NameCheck checkName(String raw, Collection<String> taken)` avec `enum NameCheck { OK, TOO_SHORT, TOO_LONG, TAKEN }` et `static String normalize(String raw)` (rognage), et `static String autoName(Collection<String> taken)` (« Station N », plus petit N libre à partir de 1).
- Produit : `scripts/run-tests.sh` compile `plugin/src/test/java` contre `dist/classes` et `HytaleServer.jar` dans un dossier de travail, puis lance `TransitTests` (classe à `main`, sans bibliothèque de test, une méthode statique par test, code de sortie non nul au premier échec avec le nom du test).

- [ ] **Étape 1 : écrire `TransitTests.nameRules`** : `checkName("  Ab  ", Set.of())` donne `TOO_SHORT`, `checkName("Abc", Set.of())` donne `OK`, une chaîne de 25 caractères donne `TOO_LONG`, `checkName("port nord", Set.of("Port Nord"))` donne `TAKEN`, `normalize("  Port Nord ")` donne `"Port Nord"`, `autoName(Set.of("Station 1", "Station 3"))` donne `"Station 2"`.
- [ ] **Étape 2 : écrire `TransitTests.stationFileRoundTrip`** : une station avec une file de 2 noms écrite par `save()` dans un dossier temporaire puis relue par `load()` donne les mêmes champs.
- [ ] **Étape 3 : écrire `scripts/run-tests.sh` et lancer** : échec attendu, classes absentes.
- [ ] **Étape 4 : écrire `TransitConstants`** (toutes les valeurs des contraintes globales) **et `TransitNetwork`**. Chargement dans `OrbisHorizonPlugin` au même endroit que `BalloonRegistry`.
- [ ] **Étape 5 : compiler et lancer les tests** : `scripts/build-plugin.sh && scripts/run-tests.sh`, sortie attendue `TransitTests: 2 OK`.
- [ ] **Étape 6 : commiter** (`Transport en commun : réseau des stations et tests`).

### Tâche 4 : caisse et pose de la station

**Fichiers :**
- Créer : `Server/Item/Items/Containers/Vehicles/Balloon_Station_Crate.json`, `plugin/src/main/java/com/keepkoolz/orbishorizon/DeployStationInteraction.java`
- Modifier : `OrbisHorizonPlugin.java` (enregistrement de `Transit_DeployStation`), `BalloonManager.java` (une surcharge de `pasteKeepingTerrain` qui protège aussi les cases d'une couche donnée), `scripts/make_crate_icons.py`, les deux `server.lang`

**Interfaces :**
- Consomme : `TransitNetwork`, `Deployables.STATION`, `Deployables.BALLOON_PUBLIC`, `STATION_DOCK_ORIGIN`, `STATION_GRASS_Y`.
- Produit : `DeployStationInteraction` (type `Transit_DeployStation`) et `static void registerStation(World world, Vector3i origin, Rotation rotation, UUID owner, String name)` qui crée la `Station`, l'enregistre et l'annonce. La tâche 7 l'appelle à la validation du nom. Tant que la tâche 7 n'existe pas, la pose appelle directement `registerStation` avec `autoName`.

- [ ] **Étape 1 : écrire `TransitTests.grassLayerCells`** : pour une forme d'essai, la fonction `static boolean keepTerrainAt(int prefabY, BlockMaterial existing)` rend vrai pour `prefabY == -1` et un bloc existant solide, faux pour un bloc vide ou non solide, faux pour toute autre hauteur. Lancer : échec attendu.
- [ ] **Étape 2 : écrire la caisse** : copie de `Camping_Tent_Big_Crate.json` sans champ `Recipe` (caisse réservée au mode créatif, décision de l'utilisateur), `PrefabPath` `Balloon_Landing_zone.prefab.json`, `Interactions.Secondary` vers `Transit_DeployStation` puis `ModifyInventory` (`AdjustHeldItemQuantity: -1`) en `Next`, comme les caisses de montgolfière. Pictogramme ajouté à `make_crate_icons.py` (quai et petite montgolfière rouge). `Offset` et `RotationOffset` déduits du prefab (plateau loin du joueur), à confirmer en jeu.
- [ ] **Étape 3 : écrire `DeployStationInteraction`** dans l'ordre de la spec (section « Pose ») : refus si le joueur n'est pas op (`StructureRemoval.isOp`, clé `transit.deploy.opOnly`), test de place (chaque case de la station hors couche d'herbe, puis chaque case de la forme de `BALLOON_PUBLIC` posée à `STATION_DOCK_ORIGIN`, tournées, sans bloc solide, sous la limite, chunks chargés), distance de 64 blocs aux autres stations, puis pose par la nouvelle surcharge de `pasteKeepingTerrain` (terrain gardé pour les cases `keepTerrainAt`), inscription au registre de verrouillage (`BalloonRegistry.add(..., STATION_KIND, uuid)`), puis `registerStation` avec `autoName`. Refus : clés `transit.deploy.noSpace`, `transit.deploy.tooClose`, `deploy.heightLimit` (existante), état `Failed`.
- [ ] **Étape 4 : compiler et lancer les tests** : `TransitTests: 3 OK`.
- [ ] **Étape 5 : commiter** (`Transport en commun : caisse et pose de la station`).

### Tâche 4b : recette de la caisse activable par un op

Décision de l'utilisateur du 10 octobre 2026 : la caisse a une recette, qu'un op active ou coupe pour tout le serveur. Recette activée, tout joueur peut poser une station. Recette coupée, seule la caisse obtenue en créatif sert, et seul un op peut la poser.

**Fichiers :**
- Créer : `plugin/src/main/java/com/keepkoolz/orbishorizon/TransitSettings.java`
- Modifier : `Server/Item/Items/Containers/Vehicles/Balloon_Station_Crate.json` (champ `Recipe` avec `KnowledgeRequired: true`), `DeployStationInteraction.java` (contrôle op seulement si la recette est coupée), `OrbisHorizonPlugin.java` (chargement du réglage, apprentissage à l'arrivée d'un joueur), `StationCommand.java` ou une nouvelle sous-commande si la tâche 10 n'est pas faite (créer alors `StationCommand.java` avec seulement `recipe`, la tâche 10 complètera), les deux `server.lang`

**Interfaces :**
- Produit : `TransitSettings.recipeEnabled()`, `static void setRecipeEnabled(boolean on)` (enregistré de façon atomique dans `transit-settings.json` du dossier de données, faux par défaut et si le fichier manque ou est illisible, avec la même règle `.bad` que `TransitNetwork`), `static void load(Path dataDir)`.
- Produit : `/orbishorizon station recipe on|off|show` (op). Sur `on`, `CraftingPlugin.learnRecipe(ref, "Balloon_Station_Crate", accessor)` pour chaque joueur connecté de chaque monde (sur le fil de son monde). Sur `off`, `CraftingPlugin.forgetRecipe` de la même façon. À l'arrivée d'un joueur dans un monde (`AddPlayerToWorldEvent`, comme `BalloonManager.onPlayerAdded`), apprendre ou oublier selon le réglage.

- [ ] **Étape 1 : écrire `TransitTests.settingsRoundTrip`** : réglage absent donne faux, `setRecipeEnabled(true)` puis rechargement donne vrai, fichier corrompu donne faux et un `.bad`. Lancer : échec attendu.
- [ ] **Étape 2 : écrire `TransitSettings`** et la recette provisoire de la caisse : établi de base, onglet Tinkering, niveau 3, `KnowledgeRequired: true`, 20 `Rock_Basalt_Brick`, 10 `Wood_Softwood_Planks`, 1 `Furniture_Village_Brazier`, 3 s. Une ligne de commentaire n'est pas possible en JSON : noter « recette provisoire, à fixer avec le prefab définitif » dans le rapport et dans `AGENTS.md` (tâche 11).
- [ ] **Étape 3 : brancher** la commande, l'apprentissage à l'arrivée et le contrôle de pose (`transit.deploy.opOnly` seulement si `recipeEnabled()` est faux). Messages `transit.recipe.on`, `transit.recipe.off`, `transit.recipe.show`.
- [ ] **Étape 4 : compiler, tests (un de plus), `deploy.sh --dry-run`.**
- [ ] **Étape 5 : commiter** (`Transport en commun : recette de la caisse activable par un op`).

### Tâche 5 : chef de gare

**Fichiers :**
- Créer : `plugin/src/main/java/com/keepkoolz/orbishorizon/StationMaster.java`, `Server/NPC/Roles/Station_Master.json` (copie du Kweebec passif retenu par le rapport de la tâche 1, sans boutique), et selon la voie de la tâche 1 soit une racine `Server/Item/RootInteractions/Transit/Station_Master_Use.json` avec son interaction Java `StationMasterInteraction.java`, soit l'action de rôle
- Modifier : `OrbisHorizonPlugin.java`, `DeployStationInteraction.java` (apparition du PNJ), les deux `server.lang`

**Interfaces :**
- Consomme : `TransitNetwork.Station`, `STATION_MASTER_POS`, la voie et les signatures du rapport de la tâche 1.
- Produit : `static UUID spawn(Store<EntityStore> store, World world, Station s)`, `static void ensure(World world, Station s)` (retrouve par UUID ou recrée, à `StartWorldEvent` et au démarrage du plugin, même méthode que `CageAnimals.recoverWorld`), `static void remove(Store<EntityStore> store, Station s)`, `static boolean reserve(Station s, UUID player)`, `static void release(Station s, UUID player)`, `static void tickReservations(Store<EntityStore> store, World world)` (levée à 8 blocs, 60 s, mort, déconnexion), et le point d'entrée `static void onTalk(Store<EntityStore> store, Ref<EntityStore> playerRef, Station s)` qui réserve puis ouvre la fenêtre (tâche 7) ou envoie `transit.master.busy`.

- [ ] **Étape 1 : écrire `TransitTests.reservationIsExclusive`** : `reserve(s, a)` vrai, `reserve(s, b)` faux, `reserve(s, a)` vrai (même joueur), `release(s, a)` puis `reserve(s, b)` vrai. Lancer : échec attendu.
- [ ] **Étape 2 : écrire le rôle et `StationMaster`** : PNJ `Frozen` et `Invulnerable` (comme `CageAnimals.capture`), nom affiché `transit.master.name` avec le paramètre `station`, UUID gardé dans la station.
- [ ] **Étape 3 : brancher la voie retenue** pour que parler au PNJ appelle `onTalk`. Le texte d'indication affiché quand on vise le PNJ remplace « Trade » : `Interactions.setInteractionHint` avec la clé `orbis_horizon.interactionHints.stationMaster` (« Press [{key}] to choose a destination » et « Appuyez sur [{key}] pour choisir une destination »), comme l'aide de la barre du dirigeable (T67). En attendant la tâche 7, `onTalk` envoie au chat la liste des destinations (clé `transit.master.list`).
- [ ] **Étape 4 : compiler, tests** (`TransitTests: 4 OK`), `deploy.sh --dry-run`.
- [ ] **Étape 5 : commiter** (`Transport en commun : chef de gare`).

### Tâche 6 : file d'une station, approche, quai et départ

**Fichiers :**
- Créer : `plugin/src/main/java/com/keepkoolz/orbishorizon/StationQueue.java`, `TransitFlight.java`
- Modifier : `BalloonFlight.java` (champ `volatile TransitFlight transit`), `BalloonManager.java` (points d'appel listés ci-dessous), `BalloonFlightSystem.java` (appel de `StationQueue.tick`), les deux `server.lang`

**Interfaces :**
- Consomme : `TransitNetwork`, `TransitConstants`, `Deployables.BALLOON_PUBLIC`, `STATION_DOCK_ORIGIN`.
- Produit : `enum QuayState { FREE, APPROACH, DOCKED, DEPARTING, ARRIVING }`, `static EnqueueResult enqueue(World world, Station s, String destination, PlayerRef chooser)` avec `enum EnqueueResult { ADDED, ALREADY_QUEUED, FULL, SELF }` (la position dans la file est lue par `positionOf(Station, String)`), `static QuayState state(Station s)`, `static int secondsLeft(Station s)`, `static void tick(Store<EntityStore> store, World world)`, `static void onDocked(World world, Station s, Vector3i origin)`, `static void onQuayCleared(Station s)`, `static void onStationRemoved(World world, Station removed)`.
- Produit : `TransitFlight` (champs : `String from`, `String to`, `Phase phase`, `int holdIndex`, `long phaseSinceMs`, `long lastProgressMs`, `double cruiseY`), `enum Phase { APPROACH, CLIMB, CRUISE, HOLD, DESCEND, DROP, VANISH }`.
- Points d'appel dans `BalloonManager`, sans autre logique :
  - `Message launchTransitApproach(Store<EntityStore> store, World world, Station s)` : crée un vol sans pilote (`driven` vrai, `pilotRef` nul) de type `BALLOON_PUBLIC`, entité à `STATION_DOCK_ORIGIN` plus 15 blocs, flamme allumée, `transit` rempli en phase `APPROACH`, sans retirer de blocs ni écrire de combustible.
  - `Message launchTransitDeparture(Store<EntityStore> store, World world, Station s, String destination)` : le chemin de `launch` sans le contrôle de combustible, passagers par `nacelleOccupants` puis `assignSeats` (12 au plus, les autres restent), `transit` en phase `CLIMB`.
  - `tickDriven` : si `flight.transit != null`, appeler `TransitAutopilot.step` (tâche 8) au lieu de la descente. En phase `APPROACH`, la descente actuelle reste utilisée et le contact appelle `finishFlight`, qui pose en blocs puis appelle `StationQueue.onDocked`.
  - Combustible et panne sèche ignorés quand `flight.kind == BALLOON_PUBLIC`.
  - `blocksBalloon` reçoit un paramètre `boolean ignoreHeight` (vrai pour un vol de transport) par une surcharge. Les appels existants gardent faux.

- [ ] **Étape 1 : écrire `TransitTests.queueRules`** : sur une station vide, `enqueue` de A donne `ADDED`, de A encore `ALREADY_QUEUED` avec `positionOf` 1, de B, C, D, E `ADDED`, de F `FULL`. `enqueue` de la station elle-même donne `SELF`. Lancer : échec attendu.
- [ ] **Étape 2 : écrire `StationQueue`** et les annonces (`transit.queue.added`, `transit.quay.docked`, `transit.quay.countdown`, `transit.quay.full`) aux joueurs à moins de 20 blocs du plateau. Le compte à rebours envoie un message à 50, 40, 30, 20 et 10 s. À 0 s : `launchTransitDeparture`, ou, sans passager, montée et disparition sans trajet (phase `VANISH`) et retrait de la destination. Le quai repasse à `FREE` quand l'entité dépasse le plateau de 20 blocs (`onQuayCleared`).
- [ ] **Étape 3 : ajouter les points d'appel dans `BalloonManager`** listés plus haut.
- [ ] **Étape 4 : persister la file** dans `stations.json` à chaque changement. Au redémarrage, une montgolfière de type `balloon_public` trouvée à quai en blocs (registre de verrouillage) remet l'état `DOCKED` avec 60 s.
- [ ] **Étape 5 : compiler, tests (`TransitTests: 5 OK`), `deploy.sh --dry-run`.**
- [ ] **Étape 6 : commiter** (`Transport en commun : file, approche, quai et départ`).

### Tâche 7 : fenêtres de destination et de nom

**Fichiers :**
- Créer : `plugin/src/main/java/com/keepkoolz/orbishorizon/DestinationPage.java`, `StationNamePage.java`, `Common/UI/Custom/Pages/Transit_Destinations.ui`, `Common/UI/Custom/Pages/Transit_StationName.ui`
- Modifier : `StationMaster.java` (`onTalk` ouvre `DestinationPage`), `DeployStationInteraction.java` (ouvre `StationNamePage` après la pose au lieu de l'appel direct avec `autoName`), les deux `server.lang`

**Interfaces :**
- Consomme : format `.ui` et API de page du rapport de la tâche 1, `StationQueue.enqueue` (tâche 6), `TransitNetwork.checkName`, `DeployStationInteraction.registerStation`.
- Produit : `DestinationPage(PlayerRef player, Station s)` et `StationNamePage(PlayerRef player, World world, Vector3i origin, Rotation rotation)`.

Le bouton de destination appelle `StationQueue.enqueue(World, Station, String destination, PlayerRef chooser)`.

- [ ] **Étape 1 : écrire `Transit_Destinations.ui`** : titre avec le nom de la station, ligne d'état du quai, liste de la file, liste de boutons de destinations (nom et distance), bouton Fermer.
- [ ] **Étape 2 : écrire `DestinationPage`** : à l'ouverture, liste de `TransitNetwork.stations(world)` sauf la station du PNJ, triée par nom, distance arrondie au bloc. Un clic appelle `enqueue` puis ferme. La fermeture, quelle qu'en soit la cause, appelle `StationMaster.release`.
- [ ] **Étape 3 : écrire `Transit_StationName.ui` et `StationNamePage`** : champ de texte et Valider. Un nom refusé affiche la raison (`transit.name.tooShort`, `tooLong`, `taken`) sans fermer. Valider appelle `registerStation` avec le nom normalisé. Fermer sans valider appelle `registerStation` avec `autoName`.
- [ ] **Étape 4 : compiler, tests, `deploy.sh --dry-run`.**
- [ ] **Étape 5 : commiter** (`Transport en commun : fenêtres de destination et de nom`).

### Tâche 8 : pilote automatique, arrivée, attente et dépose

**Fichiers :**
- Créer : `plugin/src/main/java/com/keepkoolz/orbishorizon/TransitAutopilot.java`
- Modifier : `BalloonManager.java` (un point d'appel `endTransit`), les deux `server.lang`

**Interfaces :**
- Consomme : `TransitFlight`, `StationQueue`, `TransitConstants`, `STATION_BOARDING_POINTS`, `blocksBalloon(..., true)`, `EventTitleUtil.showEventTitleToPlayer(PlayerRef, Message, Message, boolean)`.
- Produit : `static void step(Store<EntityStore> store, World world, StructureShape s, BalloonFlight f, long now)`, et les fonctions pures `static double cruiseY(int padYA, int padYB)`, `static Vector3d holdingPoint(Vector3d padCentre, Vector3d comingFrom, int index)`, `static List<Vector3d> dropPoints(Vector3i stationOrigin, Rotation rotation, int count)`.
- Produit dans `BalloonManager` : `void endTransit(Store<EntityStore> store, BalloonFlight f)` (supprime entités, parties, lumières, vue et fichier de reprise, sans pose en blocs).

- [ ] **Étape 1 : écrire `TransitTests.cruiseAndHolding`** : `cruiseY(64, 80)` donne 110. `holdingPoint` à l'index 0 est à 30 blocs du centre du plateau à l'horizontale, du côté de `comingFrom`, et l'index 1 est à 6 blocs de l'index 0. `dropPoints(..., 12)` rend 12 points, tous parmi ou décalés depuis `STATION_BOARDING_POINTS` (répartition circulaire sur la liste), tournés par la rotation.
- [ ] **Étape 2 : écrire `TransitTests.holdingPoints`** : deux arrivantes au même quai ont des points distincts, et l'ordre de dépose suit l'ordre d'entrée en attente.
- [ ] **Étape 3 : lancer** : échec attendu.
- [ ] **Étape 4 : écrire `TransitAutopilot.step`** avec les phases de la spec (section « Le vol automatique ») : `CLIMB` vers `cruiseY`, `CRUISE` en ligne droite vers la verticale du plateau d'arrivée avec un test de forme 3 s de trajet en avant, montée tant que bloqué puis hauteur gardée, arrêt jusqu'à 10 s sur des chunks non chargés puis avance case par case, `HOLD` si le quai d'arrivée n'est pas `FREE` (message `transit.arrival.waiting` avec le nombre de secondes), `DESCEND` jusqu'au contact ou au plateau, `DROP` (libère les passagers, les téléporte sur `dropPoints`, message `transit.arrival.welcome` et grand titre avec le nom de la station et « Orbis Horizon »), puis `VANISH` (montée de 20 blocs puis `endTransit`). Secours : 30 s sans progrès en `CRUISE`, `HOLD` exclu, donne une dépose par téléportation (`transit.rescue`) puis `endTransit`. L'état `ARRIVING` du quai d'arrivée est posé au début de `DESCEND` et levé à la fin de `VANISH`.
- [ ] **Étape 5 : compiler, tests (`TransitTests: 7 OK`), `deploy.sh --dry-run`.**
- [ ] **Étape 6 : commiter** (`Transport en commun : pilote automatique, attente et dépose`).

### Tâche 9 : incidents

**Fichiers :**
- Modifier : `TransitAutopilot.java`, `StationQueue.java`, `BalloonManager.java` (`recoverOne`, `onPlayerGone`), `BalloonResume.java` (champ facultatif `transitTo`), les deux `server.lang`

**Interfaces :**
- Consomme : tâches 6 et 8, `BalloonResume.writeStranded`, `restoreStrandedPassengers`.
- Produit : `static Station rerouteTarget(List<Station> stations, String from, Vector3d here)` (pure : la station de départ si elle existe, sinon la plus proche, sinon `null`).

- [ ] **Étape 1 : écrire `TransitTests.rerouteTarget`** : départ présent donne le départ, départ absent donne la plus proche, liste vide donne `null`. Lancer : échec attendu.
- [ ] **Étape 2 : changement de destination** : `StationQueue.onStationRemoved` retire la station de toutes les files (message `transit.queue.removed` aux joueurs proches), et chaque vol vers elle prend `rerouteTarget`. Avec `null`, le vol descend à la verticale jusqu'au sol, libère ses passagers (`putOnGround`) et appelle `endTransit`.
- [ ] **Étape 3 : arrêt du serveur** : `BalloonResume` écrit `transitTo` pour un vol de transport. `recoverOne` d'un tel fichier ne pose aucun bloc, supprime les entités orphelines et pose les passagers sur `dropPoints` de la station d'arrivée, ou les note dans `stranded/` s'ils sont hors ligne. Sans station d'arrivée, `rerouteTarget` puis le sol.
- [ ] **Étape 4 : déconnexion d'un passager** : il est noté dans `stranded/` avec un point de dépose de la station d'arrivée au lieu du sol sous la montgolfière. Mort et changement de mode de jeu : règles existantes, rien à changer, vérifier seulement qu'un vol de transport n'est pas reposé par `pilotDied` (il n'a pas de pilote).
- [ ] **Étape 5 : compiler, tests (`TransitTests: 8 OK`), `deploy.sh --dry-run`.**
- [ ] **Étape 6 : commiter** (`Transport en commun : incidents`).

### Tâche 10 : commandes des stations

**Fichiers :**
- Créer : `plugin/src/main/java/com/keepkoolz/orbishorizon/StationCommand.java`
- Modifier : `OrbisHorizonCommand.java`, les deux `server.lang`

**Interfaces :**
- Consomme : `TransitNetwork`, `StationQueue`, `StationMaster.remove`, `StructureRemoval.remove`, `BalloonManager.giveCrate`.

- [ ] **Étape 1 : écrire `StationCommand`** avec `list`, `debug`, `despawn` et `tp <nom>`, chacune `setPermissionGroups(OP_GROUP)` comme `BalloonCommand`. `despawn` prend la station la plus proche dans 48 blocs, retire la montgolfière à quai si elle existe, puis la station par `StructureRemoval.remove` (contenu lâché, registre, blocs), le PNJ, l'entrée du réseau, appelle `onStationRemoved` et rend une `Balloon_Station_Crate` par `giveCrate`. `tp <nom>` téléporte l'op sur le premier point de `TransitAutopilot.dropPoints` de la station nommée, dans le monde courant (recherche sans casse par `TransitNetwork.byName`, nom avec espaces accepté : lire le reste de la ligne si le jeu a un argument de texte libre, sinon accepter le nom entre guillemets, à vérifier dans le bytecode des types d'arguments), chunks chargés par `World.getChunkAsync` avant la téléportation, message `transit.cmd.tpUnknown` si le nom n'existe pas. Sorties de `list` et `debug` en anglais (diagnostic), messages de `despawn` et `tp` par `Texts.t`.
- [ ] **Étape 2 : compiler, tests, `deploy.sh --dry-run`.**
- [ ] **Étape 3 : commiter** (`Transport en commun : commandes des stations`).

### Tâche 11 : documentation et tests en jeu

**Fichiers :**
- Modifier : `AGENTS.md`, `TASKS.md`, `WIKI.md`, `README.md`, `CHANGELOG.md`

- [ ] **Étape 1 : `TASKS.md`** : nouvelle phase « Transport en commun » avec une tâche par tâche de ce plan (numéros à la suite de la dernière tâche du fichier), et une ligne dans la phase 16 (migration 0.7) : porter le transport en commun vers `SectionBlocks`.
- [ ] **Étape 2 : `AGENTS.md`** : ligne d'état, section « Transport en commun », lignes de « Où chercher quoi », arborescence, points à vérifier en jeu (les hypothèses de la spec) et tests en jeu numérotés à la suite, au minimum : pose et nom, refus de pose, chef de gare et file, réservation à 2 joueurs, approche et compte à rebours, joueur dans la nacelle au moment de la pose en blocs, départ sans passager, trajet complet avec grand titre, quai occupé à l'arrivée, obstacle en croisière, station retirée en vol, arrêt du serveur en vol, déconnexion d'un passager, `station despawn` avec caisse rendue.
- [ ] **Étape 3 : `WIKI.md` et `README.md`** dans le ton aventure des pages joueurs (pas de chiffres exacts dans le README), `CHANGELOG.md` section « Unreleased ».
- [ ] **Étape 4 : `scripts/deploy.sh --dry-run`** puis commiter (`Transport en commun : documentation`).
