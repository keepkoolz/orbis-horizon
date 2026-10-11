# Transport en commun entre stations de montgolfière

Date : 10 octobre 2026. Branche : `feature/transport-en-commun`, partie de `main` (API Hytale 0.6.8).

## Objectif

Les joueurs voyagent d'une station à une autre dans une montgolfière qui vole seule. Un joueur parle au chef de gare d'une station, choisit une destination, et une montgolfière vient se poser sur le quai. Elle attend 30 secondes, emmène les joueurs assis dans sa nacelle jusqu'à la station choisie, les dépose sur la plateforme de pré-embarquement, puis repart et disparaît.

Le réseau est public. Toute station posée dans le monde est une destination pour tous les joueurs, quel que soit son poseur.

Critères de réussite :

- Un joueur seul peut aller d'une station à une autre sans rien piloter ni ravitailler.
- Plusieurs joueurs peuvent demander des départs à la même station sans conflit (file d'attente, chef de gare réservé à un joueur à la fois).
- Deux montgolfières ne se gênent jamais sur un quai.
- Un arrêt du serveur, une mort ou une déconnexion en plein trajet ne laisse personne coincé en l'air.
- Aucun tremblement ni saccade visible de la vue pendant un trajet. Un tremblement rapide et continu de l'image peut gêner les joueurs photosensibles (exigence de l'utilisateur du 11 octobre 2026). C'est une condition bloquante pour terminer la fonctionnalité.

## Décisions prises avec l'utilisateur

| Sujet | Décision |
| --- | --- |
| Modèle de service | Trajet direct à la demande, pas de ligne en boucle |
| Après la dépose | La montgolfière remonte d'environ 20 blocs puis disparaît, elle ne reste pas à quai |
| Arrivée au quai de départ | Elle apparaît environ 15 blocs au-dessus du plateau et descend se poser |
| Choix de la destination | Un PNJ chef de gare (Kweebec), pas de ticket ni de borne |
| Réservation du PNJ | Un seul joueur à la fois tant que sa fenêtre est ouverte |
| File d'attente | Les destinations s'ajoutent à une file par station |
| Messages | Aux joueurs à moins de 20 blocs de la station : nouvelle destination, puis compte à rebours toutes les 10 s |
| Vue pendant le trajet | Caméra du serveur attachée à la montgolfière, à la première personne depuis le siège, orientation horizontale tirée au hasard sur 360° à chaque départ, sans regard libre (décision de l'utilisateur du 11 octobre 2026, après mesures : le suivi par vitesse, la monture et la chaîne de montures saccadent ou tremblent, la caméra attachée est fluide mais n'accepte pas le regard à la souris) |
| Durée d'embarquement | 30 s (décision de l'utilisateur du 11 octobre 2026, 60 s auparavant) |
| Arrivée | Message dans le chat et grand titre avec le nom de la station pour chaque passager |
| Nom de la station | Saisi dans une fenêtre juste après la pose, unique dans le monde, 3 à 24 caractères, nom automatique si le joueur ferme sans valider |
| Couche d'herbe du prefab (y = -1) | Seuls les blocs `Soil_Grass` et `Soil_Dirt` cèdent la place à un bloc solide existant. Le chemin de pierre et tous les autres blocs sont toujours posés |
| Vol | Croisière à hauteur variable avec détection d'obstacle, vitesse des montgolfières actuelles |
| Limite de construction | Ignorée en vol, vérifiée seulement pour les poses en blocs |
| Quai occupé à l'arrivée | L'arrivante attend à côté, jamais au-dessus |
| Retrait d'une station | Commande op seulement, la caisse est rendue à l'op |
| Recette de la caisse | Oui, à apprendre (`KnowledgeRequired`). Un op l'active ou la coupe pour tout le serveur (`/orbishorizon station recipe on\|off`, coupée par défaut). Recette activée, tout joueur peut poser une station. Recette coupée, seule la caisse du mode créatif sert et seul un op la pose. Contenu provisoire, à fixer avec le prefab définitif |
| Architecture | Réutiliser le vol sans pilote existant, avec un pilote automatique (approche A) |

## Les prefabs

`Server/Prefabs/Balloon_Landing_zone.prefab.json` (529 blocs, prefab d'essai) :

- Une rampe d'escaliers en basalte et en pierre monte jusqu'à une plateforme vers y = 7. C'est la zone de pré-embarquement.
- Un plateau de 5 x 5 blocs à y = 4 (x de 17 à 21, z de -4 à 0 dans le fichier) reçoit la nacelle. Un bloc vide le sépare de la rampe.
- Une hutte de 4 x 4 blocs (x de 2 à 5, z de 4 à 7) abrite le chef de gare.
- 6 `Furniture_Village_Brazier` éclairent la rampe.
- Une couche de `Soil_Grass` et de `Soil_Dirt` en y = -1 sert de socle.

`Server/Prefabs/Balloon_Public_Transport.prefab.json` (1 912 blocs) :

- Une enveloppe rouge et blanche de la taille de la grande montgolfière.
- Une nacelle de 5 x 5 blocs avec 12 `Furniture_Crude_Stool`, donc 12 passagers et pas de pilote.
- Pas de chaîne. Le brûleur est un `Metal_Goblin_Iron_Pipe_Large_Glowy_Mouthpiece` en (4, 3, 0).
- Le centre de la nacelle est en x = 4. Le prefab sera recentré sur la nacelle (x = 0, z = 0).
- 3 tabourets portent un composant vide, à retirer.

Préparation des prefabs (première étape de code) :

- Montgolfière : recentrage, composants vides retirés, brûleur remplacé par le bloc du mod `Hotair_Balloon_Public_Burner`, entrées `fluids` revues selon la règle de T51.
- Station : un des 6 braseros devient un bloc du mod qui sert de repère (`Balloon_Station_Marker`, même apparence que le brasero), pour que la reconnaissance ne dépende pas d'un bloc courant du jeu. Les positions du plateau, de la plateforme de pré-embarquement, de la case du PNJ et des points de dépose deviennent des constantes de `Deployables`, comme pour la cage de la transport.

Le prefab de la station va encore changer. Toutes ses positions passent donc par des constantes, et le générateur ou un `--check` doit signaler un écart entre ces constantes et le prefab.

## Architecture

### Nouvelles classes

Paquet `com.keepkoolz.orbishorizon`.

| Classe | Rôle |
| --- | --- |
| `TransitNetwork` | Registre des stations du monde : nom, monde, origine, rotation, UUID du chef de gare, poseur, file. Fichier `stations.json` dans le dossier de données, écrit de façon atomique comme `deployed.json`. Validation et unicité des noms. |
| `StationQueue` | File de destinations d'une station et état de son quai. Messages aux joueurs à moins de 20 blocs. |
| `TransitAutopilot` | Position cible d'un vol de transport à chaque tick : approche, montée, croisière avec obstacles, attente, descente, départ et disparition. |
| `StationMaster` | Création, suivi et recréation du PNJ chef de gare, réservation par un seul joueur. |
| `DestinationPage` | Fenêtre de choix de la destination. |
| `StationNamePage` | Fenêtre de saisie du nom. |
| `DeployStationInteraction` | Pose de la caisse de station. |
| `StationCommand` | Commandes `/orbishorizon station list`, `debug`, `despawn` et `tp` (op). |

### Nouveaux types dans `Deployables`

- `station` : prefab `Balloon_Landing_zone.prefab.json`, repère `Balloon_Station_Marker`, verrouillé comme les tentes, sans rangement par caisse vide.
- `balloon_public` : prefab `Balloon_Public_Transport.prefab.json`, repère `Hotair_Balloon_Public_Burner`, `BalloonSpec` avec le modèle de vol `Hotair_Balloon_Public` et ses variantes `_Off` et `_Boost`, pivot au centre de la nacelle, 12 sièges. Il n'a pas de caisse. Il n'entre pas dans la liste `BALLOONS`, que parcourt la reconnaissance au décollage par la chaîne.

### Changements dans le code existant

Ce sont des points d'appel. La logique du transport reste dans les nouvelles classes.

- `BalloonFlight` gagne un champ facultatif `transit` (station de départ, destination, phase, point d'attente). Quand il est rempli, `tickDriven` demande la position cible à `TransitAutopilot` au lieu de faire descendre la montgolfière.
- Combustible : ignoré pour le type `balloon_public`. La flamme reste allumée de l'apparition à la disparition, renforcée avec le son du lance-flammes pendant les montées.
- Limite de hauteur : pour un vol de transport, une case au-dessus de la limite compte comme libre. `blocksBalloon` reçoit l'information par le vol ou par un paramètre, sans changer le comportement des autres montgolfières.
- Verrouillage : la montgolfière à quai, posée en blocs, est inscrite au registre de verrouillage comme les autres. Ses tabourets restent utilisables. Le brûleur de transport n'a aucune interaction.
- La reprise après arrêt (T19), la mort (T39), la déconnexion (T21), le changement de mode de jeu (T75), les parties du modèle (T97), la vue multijoueur (T82) et les lumières (T34) sont réutilisés tels quels, avec les exceptions décrites plus bas.

### Nouveaux assets

- `Hotair_Balloon_Public_Burner.json` : apparence de `Metal_Goblin_Iron_Pipe_Large_Glowy_Mouthpiece`, état `On` avec flamme et lumière comme le brûleur actuel, sans banc de traitement ni fenêtre.
- `Balloon_Station_Marker.json` : apparence du brasero du jeu.
- `Balloon_Station_Crate.json` : caisse de déploiement avec une recette à apprendre, activée ou coupée par un op, pictogramme dessiné par `scripts/make_crate_icons.py`.
- Modèle de vol généré par un nouveau profil `public` de `scripts/prefab_to_model.py`, découpé en parties de 1 000 nœuds au plus (T97).
- Rôle de PNJ `Station_Master`, copié d'un Kweebec passif du jeu, sans boutique.
- Pages `.ui` des deux fenêtres sous `Common/UI/Custom/Pages/`.
- Clés de texte en anglais et en français dans les deux `server.lang`.

## Le cycle d'une station

### La file

- Le chef de gare propose toutes les stations du réseau sauf la sienne, triées par nom, avec la distance en blocs.
- Une destination déjà en file n'est pas ajoutée une seconde fois. Le joueur apprend sa position (« Déjà prévue, 2e départ »).
- La file compte 5 destinations au plus. Au-delà, le chef de gare demande d'attendre.
- Chaque ajout est annoncé aux joueurs à moins de 20 blocs : « Nouveau départ prévu pour X (2e dans la file). »

### États du quai

| État | Signification |
| --- | --- |
| Libre | Aucune montgolfière, aucune arrivée en cours |
| Approche | Une montgolfière de départ descend vers le plateau |
| À quai | Elle est posée en blocs, le compte à rebours tourne |
| Départ | Elle monte et n'a pas encore quitté la zone du quai |
| Arrivée | Une montgolfière venue d'ailleurs descend, dépose ses passagers ou repart |

Une montgolfière de départ ne vient que si le quai est libre. Les arrivées ont priorité sur la file.

### Arrivée au quai de départ

- Quand le quai est libre et que la file n'est pas vide, une montgolfière apparaît environ 15 blocs au-dessus du plateau, déjà allumée, orientée selon la station.
- Elle descend à la verticale, à la vitesse verticale de vol des montgolfières, et se pose sur le plateau.
- Posée, elle redevient des blocs, verrouillée, tabourets utilisables. Annonce : « La montgolfière pour X est à quai. Départ dans 30 s. »
- Un message suit toutes les 10 s, « Départ pour X dans 20 s » puis « 10 s ».

### Départ

- À 0 s, les joueurs présents dans la nacelle deviennent des passagers assis, 12 au plus, par le même chemin que `takeOff` (attribution des sièges de T21, maintien `follow` de T82).
- Les joueurs en trop restent sur le plateau avec le message « Montgolfière complète, attendez la prochaine. »
- Sans passager, la montgolfière ne fait pas le trajet. Elle monte et disparaît, et la destination quitte la file.
- Dès qu'elle est environ 20 blocs au-dessus du plateau, le quai redevient libre et la destination suivante fait venir la montgolfière suivante.

### Chef de gare réservé

La réservation tombe quand le joueur ferme la fenêtre, s'éloigne de plus de 8 blocs, se déconnecte ou meurt, ou après 60 s sans choix. Un autre joueur qui parle au PNJ pendant ce temps reçoit « Le chef de gare est occupé. »

### Changements dans le réseau

- Une station retirée disparaît de toutes les files. Les joueurs à moins de 20 blocs des stations concernées reçoivent un message.
- Si la montgolfière à quai doit partir vers une station retirée, elle reste à quai et repart vers la destination suivante, ou disparaît s'il n'y en a plus.

### Arrêt du serveur

- La file est enregistrée avec la station dans `stations.json`.
- Une montgolfière à quai au redémarrage reprend un compte à rebours de 30 s.
- Une montgolfière en approche, au départ ou en vol n'est pas reposée (voir le vol automatique).

## Le vol automatique

### Montée

La montgolfière monte à la verticale depuis le quai jusqu'à une hauteur de croisière : 30 blocs au-dessus du plus haut des deux plateaux. La limite de construction ne la borne pas.

### Croisière

- Ligne droite vers le point situé au-dessus du plateau d'arrivée, à la vitesse de vol horizontale des montgolfières (vitesse de vol du joueur divisée par 2, lue à l'exécution comme aujourd'hui).
- Elle ne tourne pas sur elle-même et garde l'orientation de la station de départ.

### Obstacles

- À chaque tick, la forme de vol est testée sur quelques secondes de trajet en avant, contre les blocs solides et les liquides (même test que les collisions actuelles, cases hors limite de construction libres).
- Si un obstacle est vu, elle cesse d'avancer, monte jusqu'à ce que la voie soit libre, puis repart. Elle garde ensuite cette hauteur jusqu'à la descente finale.
- Si les chunks devant ne sont pas chargés, elle s'arrête jusqu'à 10 s, le temps que le chargement autour des passagers les rattrape, puis elle avance case par case.
- Secours : si elle n'avance plus pendant 30 s, elle dépose ses passagers à la station d'arrivée par téléportation, avec un message, puis disparaît.

### Arrivée sur un quai libre

- À la verticale du plateau d'arrivée, elle descend jusqu'au plateau. Si un bloc a été construit au-dessus, elle s'arrête au contact.
- Les passagers sont libérés et téléportés sur la plateforme de pré-embarquement, répartis sur plusieurs points de dépose.
- Chaque passager reçoit « Bienvenue à X » dans le chat et le grand titre de `EventTitleUtil.showEventTitleToPlayer`, avec le nom de la station en titre et « Orbis Horizon » en sous-titre.
- Elle remonte d'environ 20 blocs puis disparaît. Elle ne se pose jamais en blocs à destination.

### Arrivée sur un quai occupé

- Si une montgolfière est à quai ou en approche, l'arrivante s'arrête à un point d'attente décalé d'environ 30 blocs du plateau à l'horizontale, du côté d'où elle arrive, à sa hauteur de croisière. La colonne de montée de la montgolfière à quai reste libre.
- Ses passagers reçoivent « Quai occupé, atterrissage dans N s ».
- La montgolfière à quai finit son compte à rebours et décolle. Quand elle a quitté la zone du quai, l'arrivante descend et dépose ses passagers.
- Plusieurs arrivantes se posent dans leur ordre d'arrivée, chacune avec son propre point d'attente décalé de quelques blocs.
- Les montgolfières sont des entités qui ne se touchent pas. Une partante peut croiser une arrivante en attente sans la gêner.

### Incidents

- Un passager qui meurt ou change de mode de jeu est libéré, le vol continue (règles de T39 et T75).
- Un passager qui se déconnecte est noté dans `stranded/` pour être posé sur la plateforme de pré-embarquement de la station d'arrivée à sa reconnexion.
- Si la station d'arrivée est retirée en vol, la montgolfière se dirige vers sa station de départ, ou vers la station la plus proche si celle-ci a disparu aussi. S'il ne reste aucune station, elle descend au sol à la verticale, libère ses passagers au sol et disparaît.
- Si le serveur s'arrête pendant un vol, la montgolfière n'est pas reposée en blocs. Tous ses passagers sont notés dans `stranded/` avec la plateforme de la station d'arrivée. Le fichier de reprise de T19 est écrit pour ces vols avec leur station d'arrivée. Relu au redémarrage, il ne repose aucun bloc et sert seulement à poser les passagers sur la plateforme d'arrivée, connectés ou à leur reconnexion.

## Le chef de gare et les fenêtres

### Le PNJ

- Il apparaît dans la hutte à la pose de la station, à une case définie dans le repère du prefab.
- Il est figé (`Frozen`) et invulnérable, comme les animaux de la cage (T74).
- Son UUID est enregistré avec la station. Au chargement, il est retrouvé par UUID ou recréé s'il manque, avec la même méthode que les animaux capturés.
- Son nom affiché est « Chef de gare de X ».
- Le retrait de la station retire le PNJ.

### Parler au PNJ

Deux voies, que l'essai préalable départagera :

1. Le composant `Interactions` posé sur l'entité, comme l'aide de la barre du dirigeable en vol (T56).
2. Une action propre au rôle, sur le modèle de `OpenBarterShop`.

### Fenêtre de destination

- En haut, le nom de la station et l'état du quai (libre, ou montgolfière à quai pour X avec le temps restant).
- La file actuelle.
- La liste des destinations avec leur distance. Un clic en ajoute une à la file et ferme la fenêtre.
- Un bouton Fermer.

### Fenêtre du nom

- Un champ de texte et un bouton Valider, ouverts juste après la pose.
- Un nom refusé (déjà pris, moins de 3 ou plus de 24 caractères) affiche la raison dans la fenêtre.
- Si le joueur ferme sans valider, la station reçoit « Station N » (N étant le plus petit numéro libre).
- La station n'entre dans le réseau qu'une fois nommée.

### Essai préalable

Avant le reste du code, un essai jetable vérifie en jeu :

- un PNJ figé et invulnérable qui réagit quand on lui parle, par l'une des deux voies
- une page `.ui` du mod avec une liste de boutons remplie par le serveur
- un champ de texte dont le serveur relit la valeur
- le grand titre `EventTitleUtil`.

Classes du serveur relevées pour l'essai : `PageManager`, `CustomUIPage`, `InteractiveCustomUIPage`, `BasicCustomUIPage`, `OpenCustomUIInteraction`, `EventTitleUtil`, `ShowEventTitle`.

Si les pages personnalisées s'avèrent impossibles, le repli est un choix par numéro dans le chat et un nom saisi au chat. L'utilisateur décide avant tout changement de voie.

## Pose et retrait d'une station

### Pose

La caisse `Balloon_Station_Crate` lance `DeployStationInteraction`, puis elle est consommée.

1. Si la recette est coupée, refus pour un joueur qui n'est pas op (cas d'une caisse du mode créatif passée à un joueur en survie). Si elle est activée, tout joueur peut poser.
2. Orientation selon le regard du joueur, comme les tentes.
3. Test de place : chaque case de la station doit être libre de bloc solide, sauf les cases de la couche d'herbe.
4. Les cases de la montgolfière à quai (sa forme posée sur le plateau) doivent être libres et sous la limite de construction.
5. Aucune autre station à moins de 64 blocs.
6. En cas de refus : message avec la raison, état `Failed`, la caisse reste dans l'inventaire.
7. En cas de réussite : pose du prefab, puis la couche d'herbe ne remplit que les cases vides ou non solides (même principe que le terrain gardé sous l'échelle, T24), verrouillage (type `station`), apparition du chef de gare, ouverture de la fenêtre du nom.

### Retrait

`/orbishorizon station despawn` (op, station la plus proche dans 48 blocs) :

- retire les blocs, le PNJ, la montgolfière à quai, la file et l'entrée du réseau
- les vols vers cette station changent de destination (section des incidents)
- rend une `Balloon_Station_Crate` à l'op, lâchée à ses pieds si son inventaire est plein.

### Autres commandes (op)

- `/orbishorizon station list` : nom, position et file de chaque station.
- `/orbishorizon station debug` : état du quai et des vols de la station la plus proche.
- `/orbishorizon station recipe on|off|show` : active ou coupe la recette de la caisse pour tout le serveur.
- `/orbishorizon station tp <nom>` : téléporte l'op sur la plateforme de pré-embarquement de la station nommée.

## Vérification

Le mod n'a pas de tests automatiques. Chaque étape passe par :

- `scripts/build-plugin.sh`
- `python3 scripts/prefab_to_model.py --check` pour les profils existants et le profil `public`
- `scripts/deploy.sh --dry-run`.

Une liste de tests en jeu est ajoutée à `AGENTS.md`. Certains demandent 2 joueurs : file partagée, chef de gare réservé, quai occupé à l'arrivée, passagers vus par un observateur.

## Documentation

`AGENTS.md`, une nouvelle phase dans `TASKS.md`, `WIKI.md`, `README.md`, `CHANGELOG.md` et les deux `server.lang`.

## Hors du périmètre

- Ligne en boucle, horaires, tickets payants.
- Stations privées.
- Montgolfière qui reste à quai comme décor.
- Rangement d'une station par son poseur.

## Point d'attention : migration 0.7

Cette branche part de `main`, donc de l'API 0.6.8. La branche `hytale-0.7`, en pause, devra recevoir cette fonctionnalité plus tard, avec la conversion des accès aux blocs vers `SectionBlocks` (T99 à T102). À noter dans la phase 16 de `TASKS.md`.

## Hypothèses à vérifier en jeu

- Un PNJ du mod peut ouvrir une page personnalisée du mod quand on lui parle.
- Une page `.ui` du mod peut afficher une liste de boutons générée par le serveur et renvoyer un champ de texte.
- `Frozen` et `Invulnerable` gardent le chef de gare en place sans effet de bord sur son interaction.
- Le test d'obstacle quelques secondes en avant suffit à la vitesse des montgolfières, avec les chunks chargés autour des passagers.
- Les passagers en mode `follow` restent assis pendant une croisière longue et pendant l'attente au point d'attente.
