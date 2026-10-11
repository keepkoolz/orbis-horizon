# Essai du chef de gare, des fenêtres et du grand titre : rapport

Essai jetable fait sur la branche `spike/transport-ui` (supprimée), API 0.6.8. Il répond aux quatre questions de la section « Essai préalable » de la spec. Les quatre sont validées en jeu par l'utilisateur le 10 octobre 2026.

## Résultats en jeu

- Grand titre : oui. `EventTitleUtil.showEventTitleToPlayer` affiche le titre et le sous-titre.
- Page avec liste de boutons remplie par le serveur : oui. Le clic sur un bouton généré revient au serveur avec sa valeur.
- Champ de texte relu par le serveur : oui. La valeur saisie arrive dans les données d'événement.
- PNJ figé et invulnérable qui réagit à la touche F : oui, par la voie 1. Le PNJ apparaît, F ouvre la page de destination et la sélection fonctionne.
- Défaut : le PNJ figé joue une animation de marche ou de course sur place (voir la dernière section). L'utilisateur l'avait déjà vu sur des moutons figés.

Voie retenue pour le PNJ : la voie 1, composant `Interactions` posé sur l'entité avec une racine d'interaction du mod. La voie 2 (action de rôle) n'a pas été nécessaire.

## Signatures (HytaleServer.jar, javap)

Paquet `com.hypixel.hytale.server.core.entity.entities.player.pages` :
- `Player.getPageManager()` rend le `PageManager`.
- `PageManager.openCustomPage(Ref<EntityStore>, Store<EntityStore>, CustomUIPage)`.
- `PageManager.setPage(Ref, Store, Page)` avec `Page.None` ferme la page.
- `CustomUIPage(PlayerRef, CustomPageLifetime)` avec `abstract build(Ref<EntityStore>, UICommandBuilder, UIEventBuilder, Store<EntityStore>)`, `protected close()`, `rebuild()`, `sendUpdate(UICommandBuilder[, UIEventBuilder], boolean)`, `onDismiss(Ref, Store)`. Le champ protégé `playerRef` est disponible.
- `InteractiveCustomUIPage<T>(PlayerRef, CustomPageLifetime, BuilderCodec<T>)` avec `handleDataEvent(Ref<EntityStore>, Store<EntityStore>, T)`.
- `CustomPageLifetime` : `CantClose`, `CanDismiss`, `CanDismissOrCloseThroughInteraction`. L'essai utilise `CanDismiss`.

Paquet `com.hypixel.hytale.server.core.ui.builder` :
- `UICommandBuilder.append(String uiFile)`, `append(String selecteur, String uiFile)`, `appendInline(String selecteur, String ui)`, `clear(sel)`, `remove(sel)`, `set(sel, String | Message | boolean | int | float | double)`.
- `UIEventBuilder.addEventBinding(CustomUIEventBindingType, String selecteur[, EventData[, boolean]])`.
- `EventData.of(String cle, String valeur)` et `append`. Une clé commençant par `@` demande au client de lire la valeur d'un élément (`EventData.of("@Name", "#NameInput.Value")`), sinon la valeur est un texte fixe.
- `CustomUIEventBindingType` : `Activating` (clic), `ValueChanged`, `Dismissing` et d'autres. L'essai n'utilise que `Activating`.

Données d'événement (`SpikeData`), même schéma que `WarpListPage$WarpListPageEventData` du jeu :

```java
BuilderCodec.builder(SpikeData.class, SpikeData::new)
    .append(new KeyedCodec<>("Dest", Codec.STRING), (d, v) -> d.dest = v, d -> d.dest).add()
    .append(new KeyedCodec<>("@Name", Codec.STRING), (d, v) -> d.name = v, d -> d.name).add()
    .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
    .build();
```

Les clés du codec sont celles des `EventData` (avec le `@` pour une valeur lue dans l'interface). Une clé absente de l'événement laisse le champ à null.

Titre : `EventTitleUtil.showEventTitleToPlayer(PlayerRef, Message titre, Message sousTitre, boolean majeur)` et la forme longue `(PlayerRef, Message, Message, boolean, String zone, float duree, float fonduEntrant, float fonduSortant)`.

PNJ : `NPCPlugin.get().spawnNPC(Store<EntityStore>, String role, String flock, Vector3dc, Rotation3fc)` rend un `it.unimi.dsi.fastutil.Pair<Ref<EntityStore>, INonPlayerCharacter>`. `NPCPlugin.getIndex(String role)` rend un nombre négatif si le rôle n'existe pas.

Interaction du mod : classe `SimpleInstantInteraction` avec `BuilderCodec.builder(Classe, Classe::new, SimpleInstantInteraction.CODEC)`, enregistrée par `getCodecRegistry(Interaction.CODEC).register(TYPE_ID, Classe.class, Classe.CODEC)`, `firstRun(InteractionType, InteractionContext, CooldownHandler)` appelle `world.execute(...)` puis `Player.getPageManager().openCustomPage`. L'acteur est `context.getEntity()` (le joueur).

## Le PNJ : pourquoi la voie 1 marche

- `RoleBuilderSystem` (création d'un PNJ) fait `Interactions.setInteractionId(InteractionType.Use, "*UseNPC")`. Tout PNJ a donc un composant `Interactions` dont l'entrée `Use` se remplace.
- Le `Use` à mains nues enchaîne `UseBlock` puis `UseEntity` (`Server/Item/Unarmed/Interactions/Empty.json`). `UseEntityInteraction` lit `Interactions.getInteractionId(Use)` de l'entité visée.
- Le marqueur `Interactable` est posé normalement par l'action de rôle `SetInteractable` pendant le tick du rôle. Un PNJ `Frozen` ne tique pas, donc le plugin pose `Interactable` à la main.
- Séquence qui marche après `spawnNPC` : `putComponent(Frozen)`, `putComponent(Invulnerable)`, `ensureComponent(Interactable)`, puis `Interactions.setInteractionId(Use, "Spike_Station_Use")` et `setInteractionHint("server.interactionHints.trade")` sur le composant existant.
- Racine d'interaction du mod : `Server/Item/RootInteractions/Spike/Spike_Station_Use.json` avec `{"Interactions": [{"Type": "Spike_OpenPage"}]}`. Le sous-dossier ne compte pas, l'identifiant est le nom du fichier.

### Le rôle du mod

Premier essai raté, journal serveur : `FAIL: /Server/NPC/Roles/Spike_Station_Master.json: State sensor or State setter action/motion exists without accompanying state/setter: Idle` et `State required by a parameter does not exist: $Interaction`. Le rôle copié de `Kweebec_Merchant` gardait `StartState` et `BusyStates` sans machine à états. Rôle minimal qui charge, à `Server/NPC/Roles/Spike_Station_Master.json` (identifiant = nom du fichier) :

```json
{
  "Type": "Generic",
  "DefaultNPCAttitude": "Ignore",
  "DefaultPlayerAttitude": "Neutral",
  "Appearance": "Kweebec_Rootling",
  "MaxHealth": 50,
  "Invulnerable": true,
  "DisableDamageGroups": [ "Self" ],
  "MotionControllerList": [
    { "Type": "Walk", "MaxWalkSpeed": 5, "Gravity": 10, "RunThreshold": 0.3,
      "MaxFallSpeed": 15, "MaxRotationSpeed": 360, "Acceleration": 10 }
  ],
  "Instructions": [
    { "Sensor": { "Type": "Any" }, "BodyMotion": { "Type": "Nothing" } }
  ],
  "NameTranslationKey": "server.orbis_horizon.spike.stationMaster"
}
```

## Format `.ui` qui marche

Fichiers du mod dans `Common/UI/Custom/Pages/`, chargés par le serveur avec `append("Pages/<Nom>.ui")`. Ils référencent `"../Common.ui"` comme ceux du jeu, et le client les accepte. Les textes littéraux fonctionnent (`Text: "..."`, `@Text = "..."`), pas besoin de clés `%server...`. Sources copiées dans `Assets.zip` : `Common/UI/Custom/Pages/NameRespawnPointPage.ui` (champ de texte), `WarpListPage.ui` et `WarpEntryButton.ui` (liste). Le code Java correspondant est celui de `SetNameRespawnPointPage` et `WarpListPage` (paquets `com.hypixel.hytale.builtin.beds.respawn` et `com.hypixel.hytale.builtin.teleport`).

Page de liste, `Spike_Destinations.ui` :

```
$C = "../Common.ui";
$C.@PageOverlay {}
$C.@Container {
  Anchor: (Width: 600, Height: 500);
  #Title { Group { $C.@Title { @Text = "Spike destinations"; } } }
  #Content {
    LayoutMode: Top;
    Label #Header { Style: (RenderBold: true); Anchor: (Bottom: 8); }
    Group #List { FlexWeight: 1; LayoutMode: TopScrolling; ScrollbarStyle: $C.@DefaultScrollbarStyle; }
    $C.@TextButton #CloseButton { Text: "Close"; Anchor: (Top: 8, Height: 44); }
  }
}
$C.@BackButton {}
```

Ligne de la liste, `Spike_Entry.ui` :

```
Button #Button {
  Padding: (Full: 6);
  LayoutMode: Left;
  Style: ( Hovered: ( Background: #000000(0.2), ) );
  Label #Name { Style: (RenderBold: true); FlexWeight: 1; }
  Label #Dist { Style: (TextColor: #ffffff(0.2), RenderItalics: true); }
}
```

Construction côté serveur (sélecteurs et liaisons validés) :

```java
cmd.append("Pages/Spike_Destinations.ui");
cmd.set("#Header.Text", "...");
cmd.clear("#List");
for (int i = 0; i < n; i++) {
    cmd.append("#List", "Pages/Spike_Entry.ui");
    String sel = "#List[" + i + "]";
    cmd.set(sel + " #Name.Text", nom);
    cmd.set(sel + " #Dist.Text", texte);
    events.addEventBinding(CustomUIEventBindingType.Activating, sel, EventData.of("Dest", nom));
}
events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "Close"));
```

Page du nom, `Spike_Name.ui` : copie de `NameRespawnPointPage.ui` avec `$Sounds = "../Sounds.ui"`, `$C.@DecoratedContainer`, un `$C.@TextField #NameInput`, un `$C.@TextButton #SetButton` et un `$C.@SecondaryTextButton #CancelButton`. Côté serveur :

```java
cmd.append("Pages/Spike_Name.ui");
cmd.set("#NameInput.Value", "Station 1");
events.addEventBinding(CustomUIEventBindingType.Activating, "#SetButton", EventData.of("@Name", "#NameInput.Value"));
events.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton", EventData.of("Action", "Cancel"));
```

Dans les deux pages, `handleDataEvent` appelle `close()` pour fermer. Un message d'erreur de validation dans la fenêtre du nom peut s'afficher avec un `Label #Error` (`Visible: false` au départ, `cmd.set("#Error.Visible", true)` et `#Error.Text`), comme dans `NameRespawnPointPage`, pas essayé ici. Mettre à jour une page ouverte se fait par `sendUpdate(...)`, non essayé non plus.

## PNJ qui court sur place

Constat de l'utilisateur : le PNJ figé joue une animation de marche ou de course sur place, comme des moutons figés.

Éléments relevés :
- `Frozen` est lu par `RoleSystems$BehaviourTickSystem` (requête sur le composant), `NPCFreezeCommand` et `NPCThawCommand`. Un PNJ figé n'exécute plus son rôle, donc plus d'instruction de mouvement.
- `MovementStatesSystems$AddSystem` lit aussi `Frozen` au moment où les composants de mouvement sont ajoutés.
- Les animations de déplacement d'un PNJ viennent de `MovementStates` (marche, course, au sol) diffusés aux clients. Un PNJ figé n'est plus mis à jour, donc il garde les états du dernier tick du rôle.

Hypothèse, non vérifiée : le PNJ est figé après que le contrôleur de mouvement `Walk` a déjà calculé un tick (chute, atterrissage ou vitesse résiduelle à l'apparition), et ses `MovementStates` restent dans cet état (marche ou course) que les clients continuent d'animer. Rien ne les remet à zéro puisque le rôle ne tique plus.

Correctifs proposés pour la tâche 5, tous non testés, à essayer dans cet ordre :
1. Après `Frozen`, remettre les états de mouvement au repos : récupérer `MovementStatesComponent`, mettre à faux `walking`, `running`, `sprinting`, `jumping`, `falling` et les autres, comme le plugin le fait déjà pour `sitting` des passagers (`BalloonManager.applyPose`), puis laisser le tick du serveur les diffuser.
2. Jouer une animation de repos dans le slot `Movement` par `AnimationUtils.playAnimation(ref, AnimationSlot.Movement, "Idle", true, accessor)` (même appel que `Sit` pour les passagers, voir T25), à répéter de temps en temps.
3. Ne pas figer le PNJ : garder son rôle actif avec seulement `BodyMotion: Nothing` et aucune instruction de déplacement, des attitudes `Ignore`, et poser `Invulnerable`. Le rôle de l'essai a déjà ces instructions, il faut voir si le PNJ non figé reste immobile. Il faut alors aussi empêcher qu'il soit poussé ou tombe, par exemple un `Walk` à `MaxWalkSpeed` 0.
4. Pour les animaux de la cage, qui gardent le même défaut, appliquer le même correctif si la voie 1 ou 2 marche.
Mesure à faire d'abord : lire les `MovementStates` du PNJ figé en jeu (une commande de debug) pour confirmer l'hypothèse.

## Doutes restants

- La lecture de `MovementStates` du PNJ et le correctif d'animation ne sont pas essayés.
- La fenêtre du nom avec un message d'erreur et la mise à jour de la page ouverte (`sendUpdate`) ne sont pas essayées.
- Pas d'essai à deux joueurs : deux joueurs qui ouvrent le même PNJ au même tick sont gérés côté plugin (tâche 5).
- Le nom affiché du PNJ était la clé brute dans l'essai. La tâche 5 devra poser un `DisplayNameComponent` ou une clé de langue.
