# MapTool com MCP e Codex

Este fork conecta o Codex ao MapTool aberto no computador: o agente consulta o estado da
partida e usa ferramentas para editar mapas e executar ações autorizadas. As alterações
são enviadas aos outros participantes pelo mecanismo de rede existente do MapTool.

O mestre pode habilitar criação de NPCs, gráficos de cenário, objetos diversos e
arte via ImageGen. Um catálogo persistente conecta mapa-múndi, cidades, construções
e cômodos: cada local mantém seu mapa e seus personagens entre visitas, inclusive
quando seu interior é construído somente na primeira entrada. Incêndios avançam
em passos explícitos e podem atingir ou destruir os locais internos.

A integração tem duas partes: `tools/mcp/maptool_mcp.py` é o servidor MCP por **stdio**
iniciado pelo Codex; o MapTool deste fork oferece uma ponte HTTP autenticada em
`http://127.0.0.1:27182/mcp-bridge`. A ponte é interna à integração e não é um endpoint
MCP HTTP para colocar no campo `url` do Codex.

Cada participante que quiser usar um agente executa seu próprio MapTool e seu próprio
Codex. A ponte usa o jogador conectado naquela instância, inclusive seu papel de mestre
(GM) ou jogador. O segredo local autentica a conexão entre os dois processos; ele não
concede um papel diferente no jogo. Mantenha o Codex dos jogadores conectado às
respectivas instâncias de jogador.

## Preparar o MapTool deste fork

São necessários JDK 21, Python 3.10 ou superior e Codex com suporte a MCP local por
stdio. O servidor Python usa somente a biblioteca padrão. É necessário compilar este
fork: os instaladores oficiais do RPTools não incluem esta ponte.

Clone a branch que contém a integração:

```sh
git clone --branch feat/codex-mcp https://github.com/BurguerJohn/maptool_mcp.git
cd maptool_mcp
```

Na raiz do clone, gere a distribuição local:

```sh
./gradlew --no-daemon installDist
```

No Windows PowerShell:

```powershell
.\gradlew.bat --no-daemon installDist
```

O executável fica em `build/install/MapTool/bin/MapTool`, ou
`build/install/MapTool/bin/MapTool.bat` no Windows. Para desenvolvimento, também é
possível iniciar com `./gradlew --no-daemon run` ou `.\gradlew.bat --no-daemon run`.
A compilação exige acesso aos repositórios de dependências do projeto e a execução
exige um ambiente gráfico.

## Configurar o Codex

Mescle o conteúdo de [`tools/mcp/config.example.toml`](../tools/mcp/config.example.toml)
em `~/.codex/config.toml`. Também é possível usar `.codex/config.toml` em um projeto
confiável. Substitua o caminho pelo caminho absoluto do seu clone:

```toml
[mcp_servers.maptool]
command = "python3"
args = ["/caminho/absoluto/maptool_mcp/tools/mcp/maptool_mcp.py"]
env_vars = ["MAPTOOL_MCP_TOKEN", "MAPTOOL_MCP_URL"]
startup_timeout_sec = 10
tool_timeout_sec = 30
```

No Windows, use `command = "python"` e, por exemplo,
`args = ["C:/repos/maptool_mcp/tools/mcp/maptool_mcp.py"]`. Caso o Python não esteja no
`PATH`, use também um caminho absoluto em `command`.

`env_vars` encaminha as variáveis do ambiente do Codex ao processo MCP. O token deve
estar no ambiente **antes** de iniciar o Codex. Não grave o segredo no arquivo
versionado nem o envie como mensagem ao agente. Essa configuração segue a
[documentação oficial de MCP no Codex](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).

## Iniciar uma sessão

Gere **um único segredo** para o par MapTool/Codex e inicie ambos no mesmo terminal.
O segredo precisa ter de 32 a 512 caracteres: letras, números ou a pontuação
`._~+/-`, com `=` permitido apenas no final. O exemplo gera 64 caracteres
hexadecimais. Um novo segredo só vale depois que os dois processos
forem reiniciados com o mesmo valor.

Linux ou macOS, na raiz do clone:

```sh
export MAPTOOL_MCP_ENABLED=true
export MAPTOOL_MCP_TOKEN="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
export MAPTOOL_MCP_URL=http://127.0.0.1:27182/mcp-bridge
./build/install/MapTool/bin/MapTool > /tmp/maptool-mcp.log 2>&1 &
codex
```

Windows PowerShell, na raiz do clone:

```powershell
$env:MAPTOOL_MCP_ENABLED = "true"
$env:MAPTOOL_MCP_TOKEN = python -c "import secrets; print(secrets.token_hex(32))"
$env:MAPTOOL_MCP_URL = "http://127.0.0.1:27182/mcp-bridge"
Start-Process -FilePath ".\build\install\MapTool\bin\MapTool.bat"
codex
```

Espere a janela do MapTool abrir, carregue a campanha e inicie ou entre no servidor
normalmente. No Codex, `/mcp` mostra as ferramentas conectadas;
`codex mcp list` lista a configuração registrada. Uma conexão MCP presente não
significa que já exista uma campanha pronta: a primeira consulta deve confirmar
a sessão e o mapa corretos.

Se usar o Codex em um aplicativo ou extensão de IDE, inicie esse aplicativo com as
mesmas variáveis de ambiente e reinicie a conexão MCP após alterar a configuração.
Esta configuração requer acesso local à instância do MapTool; um agente executado
em outra máquina não alcança o `127.0.0.1` do seu computador.

| Variável | Processo | Efeito |
| --- | --- | --- |
| `MAPTOOL_MCP_ENABLED` | MapTool | `true` habilita a ponte; desabilitada por padrão. |
| `MAPTOOL_MCP_TOKEN` | MapTool e MCP Python | Mesmo segredo local nos dois processos, com 32 a 512 caracteres no formato descrito acima. |
| `MAPTOOL_MCP_PORT` | MapTool | Porta local opcional; padrão `27182`. |
| `MAPTOOL_MCP_URL` | MCP Python | URL da ponte em `127.0.0.1`; padrão `http://127.0.0.1:27182/mcp-bridge`. |

Para duas instâncias no mesmo computador, escolha portas distintas e configure a URL
correspondente de cada agente. Cada par pode ter seu próprio segredo. A ponte só
escuta em loopback; não abra essa porta no roteador. Os jogadores se conectam pela
rede normal do MapTool.

## Checkboxes de criação

Na janela do MapTool, abra **MCP → Opções de criação...**. O menu está disponível
mesmo quando a ponte está desligada. A campanha guarda estas quatro opções:

| Checkbox | Chave MCP | Padrão | Efeito |
| --- | --- | --- | --- |
| Usar ImageGen para gerar imagens | `imagegen` | Desmarcado | Permite preparar geração externa e importar imagens declaradas como geradas. A categoria da imagem também precisa estar habilitada. |
| Criar NPCs com a skill de NPCs | `npc` | Marcado | Permite criar NPCs e importar suas imagens. |
| Criar gráficos de cenário com a skill de cenários | `scenery` | Marcado | Permite criar gráficos e formas na camada `BACKGROUND` e importar sua arte. |
| Criar MISC com a skill de objetos diversos | `misc` | Marcado | Permite criar objetos e formas na camada `OBJECT` e importar suas imagens. |

Somente o mestre pode modificar os checkboxes; jogadores podem consultá-los. O
servidor verifica essas opções ao executar a criação, inclusive nas ferramentas
genéricas correspondentes. Desmarcar uma categoria não apaga objetos existentes.
As mesmas opções estão em `maptool_get_content_options` e
`maptool_set_content_options`. Salve a campanha para mantê-las entre sessões.

As opções controlam a criação de conteúdo dessas categorias. As operações
estruturais de mapas, portais, topologia e portas continuam disponíveis ao mestre;
as plantas geométricas de locais construídos na primeira visita também continuam
funcionando. Desligar a arte de cenário não impede navegar pelo mundo.

**ImageGen é executado pela ferramenta de imagem disponível ao Codex.** O checkbox
não instala um provedor nem adiciona geração de imagens a uma sessão do Codex que
não a ofereça. `maptool_prepare_image` prepara o fluxo; não retorna uma imagem e
não faz uma chamada externa. As outras categorias funcionam com assets existentes
ou marcadores mesmo quando ImageGen está desligado.

## Usar as skills

A skill `maptool-session` está em `.agents/skills/maptool-session/`. Inicie o Codex
na raiz deste repositório ou em uma subpasta para que ele a descubra. É possível
invocá-la explicitamente com `$maptool-session`; ela também pode ser selecionada
automaticamente quando o pedido trata da sessão do MapTool.

Para usar a skill fora do repositório, copie essa pasta para
`~/.agents/skills/maptool-session/`, conforme a
[documentação de skills do Codex](https://learn.chatgpt.com/docs/build-skills).
A configuração MCP continua apontando para o script no clone. Reinicie o Codex se
a skill ou a configuração não aparecerem.

As skills especializadas também estão em `.agents/skills/`. Para usar o conjunto
fora deste clone, copie as seis pastas `maptool-*` para `~/.agents/skills/`:

| Skill | Quando usar |
| --- | --- |
| `$maptool-session` | Consultar a sessão, executar movimentos, abrir portas e coordenar as demais operações. |
| `$maptool-imagegen` | Gerar arte com o provedor disponível no Codex, importar a imagem e colocar o resultado no mapa. |
| `$maptool-npc` | Criar personagens não jogadores, atualizar seus dados e vinculá-los como acompanhantes. |
| `$maptool-scenery` | Construir gráficos, pisos, paredes e aparência dos mapas e interiores. |
| `$maptool-misc` | Criar móveis, baús, adereços e outros objetos da cena. |
| `$maptool-world` | Organizar locais conectados, atravessar entradas e saídas e aplicar eventos ambientais. |

Exemplos de pedidos:

- Mestre: “Use `$maptool-session` para criar uma taverna com salão, corredor e duas
  salas, usando formas e paredes, e coloque uma porta na passagem para o depósito.”
- Jogador: “Mova meu personagem duas casas para leste, respeitando o caminho e as
  paredes.”
- Jogador: “Abra a porta ao lado do meu personagem.”
- Mestre: “Depois desta ação, aplique ao personagem a condição que combinamos e
  atualize sua posição.”
- Mestre: “Troque a cena de todos para o mapa da floresta.”
- Mestre: “Use `$maptool-world` para conectar este mapa-múndi à cidade de Aurora e
  criar uma taverna cujo interior será construído quando o grupo entrar.”
- Mestre: “Use `$maptool-npc` para criar uma guarda que acompanhe o personagem Ana
  quando ele entrar e sair dos lugares.”
- Mestre: “Use `$maptool-imagegen` e `$maptool-scenery` para gerar a vista superior
  da taverna e colocá-la no mapa; prepare as paredes e a porta interativa.”
- Mestre: “A cidade começa a pegar fogo. Mostre a prévia de um passo e, quando eu
  mandar avançar, aplique as consequências também às casas.”

O agente deve consultar primeiro os mapas e tokens disponíveis, identificar os IDs
corretos e conferir o resultado das alterações. Distâncias narradas em casas precisam
ser convertidas para as coordenadas esperadas pela ferramenta, usando a grade do mapa.

## Ferramentas disponíveis

O catálogo MCP fornece os esquemas completos e os limites de cada argumento. Todos
os IDs usados nas alterações devem vir das consultas ou criações anteriores.

| Ferramenta | Acesso | Função e argumentos principais |
| --- | --- | --- |
| `maptool_get_session` | Todos | Identidade, papel, mapa atual, bloqueios, opções de conteúdo, estado `liveFollowers` e convenção de coordenadas. |
| `maptool_list_maps` | Todos | IDs dos mapas permitidos; mapas ocultos aparecem apenas para o mestre. |
| `maptool_get_map` | Todos | `mapId`; tokens permitidos com paginação por `offset` e `limit` de até 200. Use `nextOffset` quando retornado. |
| `maptool_create_map` | Mestre | `name`; opcionais `gridSize`, `unitsPerCell`, `background`, `visible`, `fog`. Cria grade quadrada. |
| `maptool_update_map` | Mestre | `mapId`; altera `name`, `visible` e `fog`. |
| `maptool_switch_scene` | Todos, com limites | `mapId` seleciona o mapa local quando o mestre permite a seleção de mapas. Só o mestre pode usar `reveal` ou `forcePlayers: true`. |
| `maptool_create_token` | Mestre | `mapId`, `name`, `x`, `y`; opcionais `owners`, `type`, `layer`, `color`, `imageAssetId`, `properties`. |
| `maptool_update_token` | Proprietário ou mestre | `mapId`, `tokenId`; jogador pode alterar `name`, `facing` e estados booleanos já configurados, se a edição estiver liberada. `properties` e `visible` exigem mestre. |
| `maptool_move_token` | Proprietário ou mestre | `mapId`, `tokenId`, `x`, `y` em pixels; valida o trajeto direto para jogadores. |
| `maptool_create_door` | Mestre | `mapId`, `x`, `y`, `width`, `height`; opcionais `name`, `open`, `locked`, `color`. |
| `maptool_set_door` | Todos, com limites | `mapId`, `doorId`, `open`; jogador também informa `actorTokenId`. Alterar `locked` exige mestre. |
| `maptool_draw_shape` | Mestre | `mapId`, `x`, `y`, `width`, `height`; `shape` é `rectangle` ou `ellipse`; opções de camada, preenchimento e contorno. |
| `maptool_update_topology` | Mestre | Retângulo com `mapId`, `x`, `y`, `width`, `height`; `type` é `VBL`, `MBL` ou `BOTH`, e `operation` é `add` ou `remove`. |

Ferramentas de conteúdo e ImageGen:

| Ferramenta | Acesso | Função e argumentos principais |
| --- | --- | --- |
| `maptool_get_content_options` | Todos | Retorna os quatro booleanos da campanha. |
| `maptool_set_content_options` | Mestre | Altera `imagegen`, `npc`, `scenery` e/ou `misc`. |
| `maptool_prepare_image` | Mestre | `name`, `category` (`npc`, `scenery` ou `misc`), `prompt`; prepara a geração externa habilitada. |
| `maptool_import_image` | Mestre | Importa PNG/JPEG pequeno com `name`, `category`, `source` (`generated` ou `existing`) e `dataBase64`; retorna `imageAssetId`. |
| `maptool_image_upload` | Mestre | Importa uma imagem maior em partes, usando `action`: `begin`, `append`, `status`, `finish` ou `cancel`. |
| `maptool_create_npc` | Mestre | `mapId`, `name`, `x`, `y`; opcionais `role`, `hp`, `maxHp`, `properties`, `imageAssetId`, `width`, `height`, `visible`. Camada `TOKEN`. |
| `maptool_create_scenery` | Mestre | `mapId`, `name`, `x`, `y`; opcionais `imageAssetId`, `width`, `height`, `visible`, `color`. Camada `BACKGROUND`. |
| `maptool_create_misc` | Mestre | Os mesmos argumentos visuais de cenário. Camada `OBJECT`. |

Ferramentas de locais e acontecimentos:

| Ferramenta | Acesso | Função e argumentos principais |
| --- | --- | --- |
| `maptool_create_location` | Mestre | `name`, `kind` (`world`, `city`, `building`, `room`, `other`); opcionais `parentId`, `mapId`, `visible`, `flammable`, `entryX`, `entryY`, `template`, `widthCells`, `heightCells` e parâmetros de grade, fundo e névoa. Sem `mapId`, registra um local planejado. |
| `maptool_list_locations` | Todos, com filtro | Pesquisa por `query`, `parentId`, `kind` e `status`; paginação `offset` e `limit` até 100. |
| `maptool_get_location` | Todos, com filtro | `locationId`; o mestre recebe também entradas, filhos e ocupantes. |
| `maptool_update_location` | Mestre | `locationId`; altera `name`, `visible` ou `flammable`. |
| `maptool_materialize_location` | Mestre | `locationId`; constrói o mapa planejado uma vez ou retorna o mapa existente. |
| `maptool_create_portal` | Mestre | `sourceLocationId`, `targetLocationId`, `x`, `y`; opcionais `targetX`, `targetY`, `label`, `bidirectional`. |
| `maptool_enter_location` | Mestre | `portalId`, `tokenId`; opcionais `bringFollowers`, `requireReach` e `forcePlayers`. Transfere o mesmo token e pode construir o destino planejado. |
| `maptool_set_npc_follow` | Mestre | `npcId`, `leaderId`, `follow: true`; `follow: false` desfaz o vínculo. |
| `maptool_configure_hazard` | Mestre | `locationId`; configura `flammable`, `fireSpread` e `integrity` de 1 a 100. |
| `maptool_ignite_location` | Mestre | `locationId`, `intensity` de 1 a 100 (padrão 10); `dryRun` permite prévia. |
| `maptool_extinguish_location` | Mestre | `locationId`, `cascade` para interiores e `dryRun` para prévia. |
| `maptool_advance_world` | Mestre | `ticks` de 1 a 20 (padrão 1); `dryRun` calcula sem aplicar. |
| `maptool_get_world_events` | Mestre | Histórico ambiental com `sinceTick`, `limit` até 100 e filtro `locationId`. |

`x` e `y` são pixels do mapa, relativos ao canto superior esquerdo do token.
`gridSize` mede pixels por célula e `unitsPerCell` indica a escala de jogo. Em uma
grade quadrada de 50 pixels, duas casas para leste alteram `x` em `+100` e mantêm
`y`. `gridType`, `gridOffsetX` e `gridOffsetY` descrevem o tipo e a origem da grade
de mapas existentes; não aplique a conversão de grade quadrada a outro tipo.
Desenhos são visuais: use topologia para bloquear visão (`VBL`) ou movimento
(`MBL`). Remover topologia de uma região também afeta os bloqueios de mapa já
existentes nessa região.

Mapas novos começam ocultos e com névoa. `reveal: true` torna o mapa selecionável
pelos jogadores; não explora sua névoa. Prepare a área revelada pela interface do
MapTool ou desabilite a névoa com `fog: false` quando essa for a intenção da mesa.
`forcePlayers: true` muda o mapa exibido aos participantes; essa ação não transfere
os tokens de um mapa para outro.

Portas criadas pelo MCP são tokens de objeto com estado persistente e bloqueios
próprios de visão e movimento. Deixe um vão na parede fixa ao criá-las. Ao abrir,
a porta perde seus próprios bloqueios e fica semitransparente; paredes sobrepostas
continuam bloqueando. `doorId` é o `tokenId` retornado para a porta.

## Gerar e importar imagens

O fluxo completo é: habilitar ImageGen e a categoria, preparar o pedido com
`maptool_prepare_image`, gerar a imagem na ferramenta disponível ao Codex, importar
o arquivo e criar um NPC, cenário ou objeto com seu `imageAssetId`. Se não houver
gerador disponível naquela sessão, o agente deve informar isso e usar um asset
existente ou marcador quando atender ao pedido. Nenhuma chave de um provedor de
imagens é gravada na campanha.

O helper lê um arquivo local e transmite o conteúdo sem preencher a conversa com
base64. Ele usa as mesmas variáveis de ambiente do MCP:

```sh
python3 tools/mcp/upload_image.py /caminho/taverna.png \
  --name "Interior da taverna" --category scenery --source generated
```

No Windows, use `python` e o caminho do arquivo correspondente. Para uma imagem
preexistente, use `--source existing`. O retorno contém o `imageAssetId`; use-o em
`maptool_create_scenery`, informando mapa, posição e tamanho em pixels. A importação
sozinha disponibiliza o asset e não o coloca no mapa.

São aceitos PNG e JPEG com até **8 MiB**, dimensão máxima de **4096 pixels por lado**
e até **8.388.608 pixels no total**. A importação direta aceita até 600 KiB; o helper
divide arquivos maiores em partes de até 600 KiB. Ele preserva o arquivo original
e não redimensiona nem comprime a imagem. Se o arquivo ultrapassar os limites,
exporte uma versão adequada antes da importação.

O upload em partes começa com `action: "begin"`, `name`, `category`, `source` e
`totalBytes`. Para cada parte, `action: "append"` recebe `uploadId`, `index` a partir
de zero e `dataBase64`. `finish` valida a imagem completa e retorna o asset; `status`
informa `receivedBytes`, `nextIndex` e, após concluir, `image`. `cancel` descarta um
upload pendente. Há no máximo dois uploads pendentes; eles expiram cinco minutos
após o início e pertencem à campanha e ao mestre que os iniciou.

O helper não repete alterações automaticamente. Em uma falha durante um upload em
partes, ele informa o `uploadId`: consulte seu `status` antes de continuar. Um recibo
concluído permite recuperar o resultado após perda da resposta; não reinicie a
criação do elemento da cena por não ter recebido essa resposta.

## Mundo, cidades e interiores

Cada local tem um `id`, um tipo, um pai opcional, um estado e, depois de construído,
um `mapId`. O mapa continua existindo quando o grupo sai: portas, tokens, objetos,
integridade e incêndio não voltam ao estado inicial na próxima visita. O catálogo
também mantém locais planejados sem mapa, pesquisáveis pelo mestre pelo nome ou
pela localização. É possível vincular um mapa já feito usando `mapId` na criação
do local, ou deixar o mapa para a primeira visita.

Os estados são `planned` (ainda sem mapa), `active`, `burning` e `destroyed`.
`maptool_materialize_location` cria a planta geométrica uma única vez, com os
parâmetros salvos. A entrada por portal também pode fazer isso. O Codex complementa
a arte e o conteúdo usando as skills habilitadas. Um lugar destruído não é reconstruído
implicitamente, mesmo se seu mapa nunca chegou a ser materializado.

`template` aceita `blank`, `world`, `settlement`, `building` e `room`. Sem escolher
um template, o tipo do local define o padrão: mundo recebe mar, terra e caminho;
cidade recebe terreno, ruas e praça; prédio recebe piso, paredes e divisão interna;
cômodo recebe piso e paredes. Prédios e cômodos incluem bloqueios reais de visão e
movimento e uma porta de entrada inicialmente aberta. O tipo `other` começa vazio.
Essas plantas usam formas do MapTool, não dependem de ImageGen e não repintam mapas
já existentes.

`widthCells` e `heightCells` vão de 6 a 60, com padrões 16 e 14. O tamanho em pixels
também precisa caber em 4096 por lado, considerando `gridSize`. A planta posiciona
`entryX/entryY` perto da entrada ao sul. Consulte os elementos que já foram criados
antes de decorar, para aproveitar a porta e os vãos existentes.

Um vínculo pai/filho define a hierarquia e a propagação ambiental. Um **portal**
define onde se entra: seu marcador fica no mapa de origem e aponta para o destino.
Com `bidirectional: true`, cria-se também a saída, que aparece quando o mapa de
destino for materializado. As coordenadas de chegada usam a escala desse destino.

O mestre executa as travessias com `maptool_enter_location`. O personagem mantém
o mesmo ID, propriedade e dados ao mudar de mapa. NPCs podem usar essas entradas
e saídas, e um vínculo com `maptool_set_npc_follow` permite acompanhar o líder nas
travessias com `bringFollowers: true`. Movimentos MCP suportados também atualizam
seguidores no mesmo mapa, parando diante de bloqueios. Quando a ponte está ativa
na instância do mestre que hospeda o servidor MapTool, o acompanhamento também
reage a movimentos normais da interface e a movimentos enviados pelos clientes
dos jogadores. Esse observador fica no host para evitar duplicação por vários
mestres; uma instância remota de mestre não assume esse papel. Consulte
`maptool_get_session.liveFollowers` para verificar o controlador. Não há cálculo
autônomo de rotas ou tomada de decisões contínua de NPCs.

`requireReach: true`, o padrão, exige proximidade do personagem à entrada. NPCs
distantes ou bloqueados permanecem na origem e são informados em `stayedTokenIds`,
com o motivo; confira esse campo em vez de presumir que todo o grupo atravessou.
`forcePlayers: true` muda a cena de todos depois da travessia; sem isso, transferir
um token não deve ser confundido com mudar a vista de todos os participantes.
Os jogadores continuam usando seu MCP para ações próprias; nesta versão, o Codex
do mestre orquestra as travessias e o conteúdo do mundo.

### Exemplo: cidade com taverna, acompanhante e incêndio

As chamadas abaixo são uma sequência de argumentos do MCP, não um script JavaScript
para executar. Substitua os identificadores em maiúsculas pelos IDs retornados nas
chamadas anteriores. Use uma campanha de teste sem névoa para observar o resultado.

1. Registre um mapa-múndi existente e guarde o `id` retornado como `MUNDO_ID`:

   ```json
   {"name":"Reino do Norte","kind":"world","mapId":"MAPA_MUNDI_ID","visible":true,"flammable":false}
   ```

   Ferramenta: `maptool_create_location`. Crie a cidade e a taverna, ainda sem mapas:

   ```json
   {"name":"Aurora","kind":"city","parentId":"MUNDO_ID","visible":true,"fog":false,"template":"settlement"}
   ```

   ```json
   {"name":"Taverna do Porto","kind":"building","parentId":"CIDADE_ID","visible":true,"fog":false,"template":"building","widthCells":16,"heightCells":14}
   ```

   Guarde os IDs como `CIDADE_ID` e `TAVERNA_ID`. Para reutilizar uma cidade já
   registrada, consulte `maptool_list_locations` com `{"query":"Aurora"}` antes
   de criar outra.

2. Crie os dois caminhos com `maptool_create_portal`:

   ```json
   {"sourceLocationId":"MUNDO_ID","targetLocationId":"CIDADE_ID","x":200,"y":100,"targetX":0,"targetY":0,"label":"Entrar em Aurora","bidirectional":true}
   ```

   ```json
   {"sourceLocationId":"CIDADE_ID","targetLocationId":"TAVERNA_ID","x":150,"y":0,"targetX":0,"targetY":0,"label":"Taverna do Porto","bidirectional":true}
   ```

   Consulte `maptool_get_location` para obter os portais e seus IDs. Os marcadores
   da cidade e da taverna aparecerão quando seus mapas forem construídos.

3. Como mestre, crie o NPC acompanhante no mapa-múndi com `maptool_create_npc`,
   próximo ao personagem do jogador, e vincule-o com `maptool_set_npc_follow`:

   ```json
   {"npcId":"GUARDA_ID","leaderId":"PERSONAGEM_ID","follow":true}
   ```

   Aproxime o personagem do ponto de entrada e use `maptool_enter_location`:

   ```json
   {"portalId":"PORTAL_DA_CIDADE_ID","tokenId":"PERSONAGEM_ID","bringFollowers":true,"requireReach":true,"forcePlayers":true}
   ```

   A cidade ganha seu mapa e os dois tokens chegam nele. Construa o cenário com
   `$maptool-scenery`. Aproxime o personagem da taverna e repita a travessia com
   `PORTAL_DA_TAVERNA_ID`. A planta já traz paredes e a porta de entrada; complemente
   seu interior com as skills habilitadas e coloque um baú. Saia pelo portal de retorno e entre novamente: o mapa e os
   objetos anteriores devem continuar presentes.

4. Configure uma taverna frágil com `maptool_configure_hazard`:

   ```json
   {"locationId":"TAVERNA_ID","integrity":40,"flammable":true,"fireSpread":true}
   ```

   Para comparar, registre outro prédio dentro de Aurora e configure
   `{"locationId":"DEPOSITO_DE_PEDRA_ID","flammable":false}`. Inicie o fogo
   na cidade com `maptool_ignite_location`:

   ```json
   {"locationId":"CIDADE_ID","intensity":20}
   ```

   Consulte a prévia de dois passos usando `maptool_advance_world`:

   ```json
   {"ticks":2,"dryRun":true}
   ```

   A prévia não modifica a campanha. Quando a mesa decidir avançar esses passos,
   chame a mesma ferramenta com `{"ticks":2}`. Neste exemplo, a taverna chega a
   integridade zero e fica destruída, a cidade cai de 100 para 60 e o depósito
   não recebe o fogo propagado. Isso também funciona se o interior da taverna
   ainda estiver planejado e nunca tiver sido visitado.

5. Confira `maptool_get_location` e `maptool_get_world_events`. A entrada da taverna
   fica desabilitada; seu mapa é preservado como registro do local destruído.
   Ocupantes são sinalizados como expostos e podem usar uma saída para evacuar:
   a integração não apaga seus tokens nem determina suas mortes. Apague o incêndio
   da cidade e dos interiores com `maptool_extinguish_location`:

   ```json
   {"locationId":"CIDADE_ID","cascade":true}
   ```

   O dano continua registrado. Salve, feche e reabra a campanha para verificar os
   mesmos locais, personagens, vínculos e estados.

### Propagação e passagem do tempo

Iniciar um incêndio afeta o local escolhido. Cada chamada de avanço propaga o fogo
pelos descendentes suscetíveis e reduz a integridade dos locais em chamas conforme
a intensidade. Um passo pode alcançar vários níveis de interiores. `fireSpread:
false` impede que o fogo daquele local seja transmitido aos descendentes;
`flammable: false` impede que ele receba a propagação, protegendo também esse
caminho para seus descendentes. Um incêndio já existente em um descendente continua
até ser apagado ou destruir o local.

Apagar o fogo não repara o dano. `integrity` permite reparos em locais não destruídos;
o sistema não ressuscita um local destruído. Uma entrada em local destruído é
bloqueada, enquanto as saídas para locais acessíveis continuam disponíveis para
evacuar ocupantes. O mapa arquivado pode permanecer visível durante a evacuação;
o estado `destroyed` continua valendo mesmo nesse período.

O relógio é narrativo: um tick não equivale automaticamente a uma rodada de D&D ou
a uma duração em segundos. Não há temporizador que execute passos sozinho. O Codex
ou o mestre decide quando avançar, e `dryRun` permite inspecionar o efeito previsto.
O fogo sinaliza exposição de PCs e NPCs e acrescenta marcadores visuais, mas não
calcula dano de combate, pontos de vida, salvamentos ou morte.

## Permissões e limites

O mestre prepara mapas, personagens, desenhos, bloqueios e portas, e decide mudanças
de cenário. Jogadores usam o agente para ações permitidas aos seus próprios tokens
visíveis. A consulta de jogador retorna apenas seus próprios tokens visíveis e as
portas e marcadores de entrada MCP visíveis no mapa atual; não retorna outros personagens, propriedades
ou estados dos tokens, nem o estado de tranca das portas. A ponte verifica a sessão
e as permissões em cada chamada; nomes ou textos
enviados pelo agente não substituem a identidade conectada.

A movimentação de jogador aplica as restrições suportadas da sessão e uma checagem
conservadora de trajeto direto. Ela não calcula automaticamente uma rota em volta
de paredes. Divida um trajeto em trechos permitidos quando necessário. Abrir uma
porta exige um personagem do tipo `PC`, do jogador, na camada `TOKEN`, a no máximo
uma célula da porta pela distância entre as bordas dos objetos. A porta precisa
estar visível e destrancada, e os bloqueios aplicáveis da sessão são respeitados.
Paredes ou bloqueios entre o personagem e a porta também impedem a interação.

A criação de mapas usa geometria e recursos do MapTool; as skills também podem
coordenar imagens geradas por uma ferramenta disponível ao Codex. Esta integração
não gera automaticamente campanhas completas, não executa macros arbitrárias e
não é um motor de regras de D&D ou de outro sistema. O movimento
via MCP não executa macros de movimento da campanha. O agente pode
aplicar alterações determinadas pelo jogador ou pelo mestre, mas não deve inventar
resultados de dados, custos de movimento ou resoluções de combate.

O estado é consultado sob demanda. A exceção é o acompanhamento de NPCs pelo host
mestre com a ponte ativa, que observa movimentos de tokens para atualizar seus
seguidores. Não há observador que interprete todas as ações da mesa, rotina contínua
de decisão de personagens ou avanço automático de incêndios. As publicações entre mapas usam o mecanismo de rede nativo
e não são uma transação distribuída: se uma travessia perder a conexão, confira
origem e destino antes de repetir a ação. As listas de iniciativa pertencem aos mapas
e não são transferidas junto dos tokens; reorganize a iniciativa ao mudar de local
quando a mesa precisar continuar um combate.

Para renomear ou revelar um local cadastrado, use `maptool_update_location` ou
`maptool_update_map`: essas operações mantêm o catálogo e seus marcadores sincronizados.
Alterações feitas diretamente nas propriedades do mapa pela interface podem exigir
uma atualização correspondente do local pelo MCP.

Salve a campanha pela interface do MapTool para manter as alterações entre sessões.
O catálogo, as opções, os portais, os vínculos e o histórico ambiental ficam em
metadados reservados da campanha. Não apague ou edite diretamente o registro MCP
pela interface de tokens; use as ferramentas correspondentes. Antes de usar uma
campanha importante, execute o roteiro abaixo em uma campanha de teste.

## Validação para desenvolvimento

Na raiz do repositório, execute os testes Python:

```sh
python3 -m unittest discover -s tools/mcp -p 'test_*.py' -v
./gradlew --no-daemon :compileJava :test --tests 'net.rptools.maptool.mcp.*' spotlessCheck
```

No Windows, substitua `python3` por `python` e `./gradlew` por `.\gradlew.bat`.
Os testes automatizados exercitam o
protocolo e as verificações que não dependem de uma partida gráfica. Eles não
substituem a validação com dois clientes reais.

### Roteiro manual com mestre e jogador

Este é um procedimento para executar no ambiente de jogo; a presença do roteiro
não afirma que o teste gráfico já foi executado.

1. Abra duas instâncias deste fork, com pontes em portas diferentes se estiverem no
   mesmo computador. Inicie um servidor na primeira como mestre e conecte a segunda
   como jogador com outro nome. Ligue um Codex a cada instância.
2. Como mestre, crie dois mapas inicialmente com `fog: false` e dois tokens.
   Atribua um token ao jogador, mantenha
   o outro sob outro proprietário e coloque um elemento oculto para o mestre.
3. Como jogador, consulte a sessão e os tokens. Confirme que informações ocultas não
   são entregues e que mover o token de outro proprietário é recusado.
4. Mova o token do jogador por um trecho livre e confira a posição nas duas janelas.
   Coloque um bloqueio de movimento no trajeto e confirme que atravessá-lo é recusado.
5. Como mestre, crie uma porta fechada em uma passagem. Como jogador, tente abri-la
   de longe, aproxime seu token e tente novamente. Confirme o bloqueio e a passagem
   nas duas janelas. Repita com uma porta trancada.
6. Como mestre, altere um token e troque o cenário dos participantes. Confirme os
   resultados no outro cliente e que o jogador não recebe permissão para preparar
   mapas ou forçar o cenário dos demais.
7. Use um segredo incorreto e confirme a recusa de autenticação. Feche o MapTool e
   confirme que a próxima ação informa indisponibilidade, sem anunciar sucesso.
8. Salve, feche e reabra a campanha para conferir a persistência dos objetos criados.
9. Abra **MCP → Opções de criação...** como mestre, desmarque uma categoria e
   confirme que a ferramenta correspondente recusa novas criações. Confira que o
   jogador não pode alterar os checkboxes. Salve e reabra a campanha para verificar
   as opções; reabilite a categoria antes dos próximos passos.
10. Importe um PNG/JPEG por `upload_image.py`, incluindo um arquivo acima de 600 KiB
    para exercitar o envio em partes. Crie seu elemento e confira escala e imagem
    no outro cliente. Com ImageGen desmarcado, confirme que `source: generated`
    é recusado. Se houver gerador disponível no Codex, execute também o fluxo real.
11. Execute o exemplo de cidade e taverna. Entre, saia e retorne com um NPC vinculado;
    confirme que os IDs são preservados e que portas e objetos mantêm seu estado.
    Com a ponte ativa no mestre host, mova o personagem pela interface do jogador
    e confira o acompanhamento. Coloque uma parede no caminho do NPC e confira
    que ele permanece antes do bloqueio.
12. Execute primeiro a prévia do incêndio e confira que ela não mudou a campanha.
    Aplique os passos, verifique o prédio protegido e o local destruído, e evacue
    um ocupante por uma saída. Salve e reabra para verificar os estados ambientais.

### Diagnóstico rápido

| Sintoma | Verificação |
| --- | --- |
| Conexão recusada | Este fork está aberto, `MAPTOOL_MCP_ENABLED=true` estava definido ao iniciar e porta/URL coincidem? |
| Falha de autenticação | Os dois processos herdaram exatamente o mesmo `MAPTOOL_MCP_TOKEN`? |
| Ferramentas não aparecem | Confira o caminho absoluto do script, a versão do Python, o TOML e `/mcp`; reinicie o Codex após mudanças. |
| Permissão negada | Confira se esta instância entrou como mestre ou jogador e se o token pertence ao jogador conectado. |
| Movimento recusado | Consulte o estado atual, a visibilidade, os bloqueios e as restrições da sessão; tente um trecho permitido. |
| Categoria de criação desabilitada | Confira os checkboxes da campanha na instância do mestre e `maptool_get_content_options`. |
| ImageGen marcado, mas sem imagem | Confira se o Codex tem uma ferramenta de geração disponível; preparar o pedido não gera o arquivo. |
| Upload interrompido | Consulte `maptool_image_upload` com `action: status` e o `uploadId`; uploads pendentes expiram após cinco minutos. |
| Não foi possível entrar em um local | Confira a identidade do mestre, a proximidade da entrada, o destino e seu estado; locais destruídos não podem ser recriados pela entrada. |
| Porta já em uso | Configure outro `MAPTOOL_MCP_PORT` e a mesma porta em `MAPTOOL_MCP_URL`; reinicie os processos. |

Para desabilitar a ponte, feche o MapTool e reinicie sem `MAPTOOL_MCP_ENABLED=true`.
