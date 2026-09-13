---
name: maptool-npc
description: Criar NPCs no MapTool, manter seus dados e configurar acompanhamento de personagens entre mapas e locais. Use para habitantes, aliados, inimigos ou acompanhantes de uma partida, respeitando a opção de criação de NPCs da campanha.
---

# NPCs da campanha

Consulte `maptool_get_session`, `maptool_get_content_options` e o mapa de destino.
A criação exige mestre e `npc: true`. Procure primeiro o personagem existente para
não duplicar um NPC que saiu de uma casa, acompanhou o grupo ou mudou de cenário.
Nomes podem se repetir; acompanhe o `tokenId` persistente e sua localização atual.

Use `maptool_create_npc` com `mapId`, `name`, `x` e `y`. Acrescente `role`, `hp`,
`maxHp` e `properties` somente quando conhecidos ou definidos no pedido. Um NPC
sem ficha completa continua sendo um token válido; não invente estatísticas de
combate para preencher lacunas. A criação é na camada `TOKEN`.

Se houver um `imageAssetId` adequado, reutilize-o. Para gerar uma nova aparência,
use a skill `maptool-imagegen` quando disponível e quando `imagegen` estiver
habilitado. A criação sem imagem produz um marcador e não exige geração externa.
O NPC precisa existir no mapa além de ter uma imagem importada.

Para um NPC acompanhar o grupo, o mestre usa `maptool_set_npc_follow` com `npcId`,
`leaderId` e `follow: true`. O líder pode ser outro token; consulte o vínculo
existente antes de trocar o líder. Use `follow: false` para encerrar o vínculo.
Não atribua propriedade de jogador ao NPC apenas para permitir esse comportamento.

Travessias de locais usam `maptool_enter_location`, com `bringFollowers: true`
quando o grupo deve seguir junto. Essa operação preserva os tokens; não crie
cópias de cada NPC no interior. `maptool_switch_scene` apenas seleciona o mapa,
portanto não substitui uma travessia. O acompanhamento é determinístico. Quando
a ponte está ativa na instância do mestre que hospeda o servidor, o observador
do host também reage a movimentos normais de tokens pela interface e pelos
clientes dos jogadores. Um mestre remoto não executa esse observador. Consulte
`maptool_get_session.liveFollowers` antes de prometer acompanhamento automático;
confira `stayedTokenIds` após travessias, pois NPCs distantes ou bloqueados podem
ficar na origem. Não há IA que
decide rotas, contorna bloqueios ou toma decisões contínuas de personagens.

Depois de uma transição, consulte o local e o mapa de destino. Interprete exposição
ao fogo como estado ambiental. O sistema de incêndio não calcula pontos de vida,
mortes, salvamentos ou decisões de fuga de NPCs: aplique apenas a resolução já
estabelecida pela mesa. Para um NPC fugir, o mestre executa sua movimentação ou
travessia pelas saídas existentes.
