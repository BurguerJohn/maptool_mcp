---
name: maptool-world
description: Operar locais conectados e persistentes do MapTool, como mundo, cidade, casa e cômodo, incluindo entradas, saídas, acompanhantes e incêndios que se propagam aos interiores. Use para navegação entre locais e consequências ambientais na campanha.
---

# Mundo e interiores persistentes

Consulte `maptool_get_session` e os esquemas atuais. Alterações de locais, travessias,
vínculos de NPCs e eventos ambientais são orquestradas pelo Codex conectado como
mestre. O MCP do jogador mantém seu acesso às ações permitidas dos próprios
personagens; não habilite privilégios de mestre para resolver uma travessia.

## Encontrar ou construir o lugar

Use `maptool_list_locations`, com `query`, `parentId` ou `kind` conforme necessário,
e leia o resultado com `maptool_get_location`. Siga a paginação. Um lugar planejado
já tem identidade e estado mesmo sem mapa materializado. Reutilize o `locationId`
quando o grupo retornar, em vez de criar outra cópia pelo mesmo nome.

Crie o catálogo com `maptool_create_location`: `kind` pode ser `world`, `city`,
`building`, `room` ou `other`; `parentId` expressa a hierarquia. Use `mapId` para
vincular um mapa existente ou omita-o para construção posterior. O pai é definido
na criação. Uma relação pai/filho não é uma passagem física: crie também o portal.

`template` escolhe `blank`, `world`, `settlement`, `building` ou `room`; sem escolha,
o tipo do local define uma planta correspondente. `widthCells` e `heightCells`
medem células (6 a 60), e o tamanho total precisa caber em 4096 pixels por lado.

`maptool_materialize_location` constrói o mapa planejado uma única vez e o reutiliza
depois. A planta usa formas; prédios e cômodos incluem paredes com topologia e
porta de entrada aberta. Consulte esses elementos antes de complementar arte,
NPCs e objetos, evitando duplicar portas ou obstruir os vãos existentes. Materializar
não significa que uma imagem tenha sido gerada pelo ImageGen. Estados ambientais também
valem para interiores ainda não materializados; não reconstrua um lugar destruído
para fazê-lo reaparecer durante uma visita.

## Entrar, sair e acompanhar

Use `maptool_create_portal` com `sourceLocationId`, `targetLocationId`, `x` e `y`.
As coordenadas pertencem ao mapa de origem; `targetX/targetY` pertencem ao destino.
Use `bidirectional: true` quando for preciso criar também a saída correspondente.

Para entrar, execute `maptool_enter_location` com `portalId` e `tokenId` do
personagem; preserve `requireReach: true` para uma interação normal. A ferramenta
transfere o mesmo token entre mapas e pode materializar o destino planejado.
`bringFollowers: true` leva os NPCs vinculados ao líder. Configure esses vínculos
com `maptool_set_npc_follow`; não recrie o grupo ao trocar de lugar. Confira
`stayedTokenIds`: seguidores distantes ou bloqueados podem ficar na origem.

Use `forcePlayers: true` somente quando o pedido inclui mudar a cena de todos.
Uma travessia individual e a cena exibida para toda a mesa são decisões diferentes.
`maptool_switch_scene` apenas seleciona uma cena e não transfere entidades.
Confira o mapa de origem e o destino após uma travessia, inclusive os seguidores.

## Incêndio e destruição

`maptool_configure_hazard` define `flammable`, `fireSpread` e `integrity` do local.
`flammable: false` impede que o local receba o fogo propagado; `fireSpread: false`
interrompe a propagação a seus descendentes. Não altere esses campos para evitar
uma consequência já resolvida pela mesa sem que a ação do usuário inclua isso.

Use `maptool_ignite_location` para iniciar o fogo no alvo. Ele avança apenas com
`maptool_advance_world`: cada tick representa um passo narrativo explícito, não
um número fixo de segundos. Em cada passo, o fogo alcança os descendentes suscetíveis
e reduz a integridade dos locais em chamas. Use `dryRun: true` para inspecionar
consequências quando isso ajudar a decidir a ação; uma prévia não aplica o evento.
Não transforme “a cidade começa a pegar fogo” em vinte passos de destruição.

`maptool_extinguish_location` apaga o fogo e aceita `cascade: true` para os locais
internos; apagar o fogo não desfaz o dano. Um local destruído permanece arquivado
e inacessível por suas entradas, com saídas para evacuação. Personagens expostos
são sinalizados; o sistema não decide dano de combate ou morte. Execute fugas e
outras ações conforme a resolução do mestre.

Consulte `maptool_get_world_events` e os locais afetados após aplicar eventos.
Salve a campanha no MapTool para manter o catálogo, os vínculos e os estados
entre sessões. Se uma alteração expirar, consulte o estado antes de repetir,
especialmente criação, travessia e avanço do relógio.
