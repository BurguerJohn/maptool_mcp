---
name: maptool-scenery
description: Construir gráficos de cenário para mapas do MapTool, usando assets existentes, ImageGen habilitado ou formas e topologia. Use para mapas de mundo, cidades e interiores, pisos, paisagens e construções visuais.
---

# Gráficos de cenário

Consulte sessão, opções de conteúdo e mapa. Criar gráficos exige mestre e
`scenery: true`. Para mundo, cidade e interior conectados, descubra primeiro o
local no catálogo e reutilize seu mapa. Materialize um local planejado quando
for necessário construí-lo, sem gerar um novo mapa a cada visita.

Separe as coordenadas da imagem das coordenadas da grade: `x/y` e `width/height`
das ferramentas são pixels do mapa. Defina a escala a partir de `gridSize` e
`unitsPerCell`, preserve o espaço para tokens e determine os pontos de entrada.
Uma imagem da cidade e o interior de uma casa podem ter escalas diferentes.

Para um asset pronto, use `maptool_create_scenery` com `mapId`, `name`, `x`, `y`,
`imageAssetId`, `width` e `height`. A camada é `BACKGROUND`; use a ferramenta de
MISC quando o elemento deve ficar na camada `OBJECT`. Para arte
nova, use `maptool-imagegen` quando disponível e `imagegen` estiver habilitado;
importe o resultado antes de criar o elemento. Sem gerador, formas ou marcadores
podem atender a um pedido esquemático, mas não são uma ilustração gerada.

Use `maptool_draw_shape` para pisos e contornos e `maptool_update_topology` para
bloqueios reais de visão e movimento. A topologia deve deixar um vão para portas
criadas com `maptool_create_door`; abrir a porta não remove uma parede permanente
que esteja por cima dela. A arte de uma casa não cria seu interior: conecte o
local interno por um portal, seguindo o fluxo de locais do MCP.

Prepare visibilidade e névoa antes de apresentar um cenário. Mapas novos podem
estar ocultos e com névoa; revelar o mapa não explora a névoa automaticamente.
Confira a escala, a camada, as passagens e os IDs dos elementos criados antes
de trocar o cenário dos participantes.

Ao representar incêndio ou ruínas, consulte o estado persistente do local.
Atualize os gráficos para ilustrar esse estado sem restaurar um local destruído
ou substituir sua identidade por outra. Desenhar chamas, por si só, não inicia
a propagação de fogo; use as ferramentas ambientais quando isso fizer parte
da ação autorizada.
