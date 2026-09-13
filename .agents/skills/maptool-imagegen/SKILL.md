---
name: maptool-imagegen
description: Gerar e importar arte para NPCs, cenários ou objetos do MapTool quando o usuário pedir imagens e a opção ImageGen da campanha estiver habilitada. Coordena a ferramenta de imagem disponível no Codex com a importação de assets pelo MCP.
---

# Arte gerada para o MapTool

Consulte `maptool_get_session` e `maptool_get_content_options`. A operação exige
mestre, `imagegen: true` e a categoria correspondente habilitada: `npc`, `scenery`
ou `misc`. Um checkbox habilita o fluxo; não instala uma ferramenta de geração nem
concede acesso a um provedor. Não altere as opções para contornar uma recusa.

Descubra se esta sessão do Codex oferece uma ferramenta de geração de imagens e
use suas instruções próprias. `maptool_prepare_image` recebe `name`, `category` e
`prompt` e prepara o pedido; a resposta dessa ferramenta **não é uma imagem**.
Se o provedor não estiver disponível, diga isso. Prossiga com um asset existente
ou um marcador geométrico quando isso atender ao pedido, distinguindo o substituto
de uma ilustração gerada.

Para cenários, descreva vista superior, escala, orientação, materiais e vãos de
portas; evite uma grade desenhada na imagem quando a grade do MapTool já cumpre
essa função. Para tokens e objetos, peça enquadramento e fundo adequados à camada.
Se a tarefa for editar uma arte existente, forneça essa imagem ao gerador; não
prometa preservar sua aparência a partir apenas de uma descrição textual.

Depois de obter o arquivo PNG ou JPEG, importe-o com `source: "generated"`. Use
`source: "existing"` somente para uma imagem preexistente importada sem geração.
Prefira o helper do clone, que lê o arquivo local e evita colocar base64 no contexto:

```sh
python3 /caminho/absoluto/maptool_mcp/tools/mcp/upload_image.py \
  /caminho/arte.png --name "Taverna" --category scenery --source generated
```

O helper herda `MAPTOOL_MCP_TOKEN` e `MAPTOOL_MCP_URL` do ambiente, como o MCP. Não
imprima o segredo nem o inclua no comando. Ele não transforma a imagem: respeite
os limites de formato, bytes e dimensões retornados pelo catálogo MCP. Imagens
maiores que um pedido são enviadas em partes por `maptool_image_upload`, até 8 MiB.
Se uma parte expirar, consulte `action: "status"` com o `uploadId` informado antes
de retomar; não reinicie o upload nem repita `finish` sem verificar seu resultado.

Use o `imageAssetId` retornado em `maptool_create_npc`, `maptool_create_scenery` ou
`maptool_create_misc`, conforme o pedido. Informe `mapId`, posição e tamanho com
base no mapa consultado. Importar um asset não o coloca na cena. Confira o token
criado antes de informar que a arte está no mapa.

Uma imagem de parede, porta ou incêndio é visual. Bloqueios, portas interativas e
estado de incêndio precisam das ferramentas específicas do MapTool; não deduza
que a imagem criou essas mecânicas.
