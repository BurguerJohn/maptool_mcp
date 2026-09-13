---
name: maptool-misc
description: Criar elementos diversos no MapTool, como móveis, baús, adereços, tesouros visuais e marcadores de cena. Use para objetos que não sejam personagens nem o fundo principal de um mapa, respeitando a opção MISC da campanha.
---

# Objetos e elementos diversos

Consulte `maptool_get_session`, `maptool_get_content_options` e os objetos do mapa
para identificar destino, escala e possíveis duplicatas. A criação exige mestre
e `misc: true`. Use `maptool_create_misc` com `mapId`, `name`, `x`, `y` e o tamanho
adequado em pixels. O objeto é criado na camada `OBJECT`.

Reutilize um `imageAssetId` existente quando apropriado. Para uma nova ilustração,
use `maptool-imagegen` quando disponível e habilitado; importe a arte na categoria
`misc` antes de colocá-la no mapa. Sem imagem, `color` permite um marcador. Nomeie
o objeto para que o agente consiga localizá-lo novamente e preserve seu `tokenId`.

Um baú desenhado não executa uma macro nem implementa regras de saque. Estado,
propriedades e conteúdo narrativo só devem mudar conforme o pedido e a resolução
da mesa. Use `maptool_update_token` para dados suportados e mantenha as propriedades
reservadas do MCP intactas.

Para uma porta utilizável, prefira `maptool_create_door` e `maptool_set_door`, que
mantêm o bloqueio e o estado de abertura. Para uma entrada em cidade ou casa, use
um portal do catálogo de locais; um marcador MISC isolado não transfere tokens.
Uma cama, mesa ou ruína visual só bloqueia passagem se a topologia correspondente
for configurada.

Após criar ou alterar os objetos, consulte o mapa. Relate o efeito visual e qualquer
efeito mecânico realmente aplicado, sem transformar um adereço em inventário,
interação automática ou item com regras que a campanha não definiu.
