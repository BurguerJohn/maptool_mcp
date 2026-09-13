# MapTool com MCP e Codex

Este fork conecta o Codex ao MapTool aberto no computador: o agente consulta o estado da
partida e usa ferramentas para editar mapas e executar ações autorizadas. As alterações
são enviadas aos outros participantes pelo mecanismo de rede existente do MapTool.

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

## Usar a skill

A skill `maptool-session` está em `.agents/skills/maptool-session/`. Inicie o Codex
na raiz deste repositório ou em uma subpasta para que ele a descubra. É possível
invocá-la explicitamente com `$maptool-session`; ela também pode ser selecionada
automaticamente quando o pedido trata da sessão do MapTool.

Para usar a skill fora do repositório, copie essa pasta para
`~/.agents/skills/maptool-session/`, conforme a
[documentação de skills do Codex](https://learn.chatgpt.com/docs/build-skills).
A configuração MCP continua apontando para o script no clone. Reinicie o Codex se
a skill ou a configuração não aparecerem.

Exemplos de pedidos:

- Mestre: “Use `$maptool-session` para criar uma taverna com salão, corredor e duas
  salas, usando formas e paredes, e coloque uma porta na passagem para o depósito.”
- Jogador: “Mova meu personagem duas casas para leste, respeitando o caminho e as
  paredes.”
- Jogador: “Abra a porta ao lado do meu personagem.”
- Mestre: “Depois desta ação, aplique ao personagem a condição que combinamos e
  atualize sua posição.”
- Mestre: “Troque a cena de todos para o mapa da floresta.”

O agente deve consultar primeiro os mapas e tokens disponíveis, identificar os IDs
corretos e conferir o resultado das alterações. Distâncias narradas em casas precisam
ser convertidas para as coordenadas esperadas pela ferramenta, usando a grade do mapa.

## Ferramentas disponíveis

O catálogo MCP fornece os esquemas completos e os limites de cada argumento. Todos
os IDs usados nas alterações devem vir das consultas ou criações anteriores.

| Ferramenta | Acesso | Função e argumentos principais |
| --- | --- | --- |
| `maptool_get_session` | Todos | Identidade, papel, mapa atual, bloqueios e convenção de coordenadas. |
| `maptool_list_maps` | Todos | IDs dos mapas permitidos; mapas ocultos aparecem apenas para o mestre. |
| `maptool_get_map` | Todos | `mapId`; tokens permitidos com paginação por `offset` e `limit` de até 200. Use `nextOffset` quando retornado. |
| `maptool_create_map` | Mestre | `name`; opcionais `gridSize`, `unitsPerCell`, `background`, `visible`, `fog`. Cria grade quadrada. |
| `maptool_update_map` | Mestre | `mapId`; altera `name`, `visible` e `fog`. |
| `maptool_switch_scene` | Todos, com limites | `mapId` seleciona o mapa local. Só o mestre pode usar `reveal` ou `forcePlayers: true`. |
| `maptool_create_token` | Mestre | `mapId`, `name`, `x`, `y`; opcionais `owners`, `type`, `layer`, `color`, `imageAssetId`, `properties`. |
| `maptool_update_token` | Proprietário ou mestre | `mapId`, `tokenId`; jogador pode alterar `name`, `facing` e estados booleanos já configurados, se a edição estiver liberada. `properties` e `visible` exigem mestre. |
| `maptool_move_token` | Proprietário ou mestre | `mapId`, `tokenId`, `x`, `y` em pixels; valida o trajeto direto para jogadores. |
| `maptool_create_door` | Mestre | `mapId`, `x`, `y`, `width`, `height`; opcionais `name`, `open`, `locked`, `color`. |
| `maptool_set_door` | Todos, com limites | `mapId`, `doorId`, `open`; jogador também informa `actorTokenId`. Alterar `locked` exige mestre. |
| `maptool_draw_shape` | Mestre | `mapId`, `x`, `y`, `width`, `height`; `shape` é `rectangle` ou `ellipse`; opções de camada, preenchimento e contorno. |
| `maptool_update_topology` | Mestre | Retângulo com `mapId`, `x`, `y`, `width`, `height`; `type` é `VBL`, `MBL` ou `BOTH`, e `operation` é `add` ou `remove`. |

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

## Permissões e limites

O mestre prepara mapas, personagens, desenhos, bloqueios e portas, e decide mudanças
de cenário. Jogadores usam o agente para ações permitidas aos seus próprios tokens
visíveis. A consulta de jogador retorna apenas seus próprios tokens visíveis e as
portas MCP visíveis no mapa atual; não retorna outros personagens, propriedades
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

A criação de mapas usa geometria e recursos do MapTool. Esta integração não inclui
geração de ilustrações por IA, geração automática de campanhas completas, execução
arbitrária de macros ou um motor de regras de D&D ou de outro sistema. O movimento
via MCP não executa macros de movimento da campanha. O agente pode
aplicar alterações determinadas pelo jogador ou pelo mestre, mas não deve inventar
resultados de dados, custos de movimento ou resoluções de combate.

O estado é consultado sob demanda. Não há observador autônomo de todas as ações da
mesa nem rotina contínua de decisão de personagens. Salve a campanha pela interface
do MapTool para manter as alterações entre sessões. Antes de usar uma campanha
importante, execute o roteiro abaixo em uma campanha de teste.

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

### Diagnóstico rápido

| Sintoma | Verificação |
| --- | --- |
| Conexão recusada | Este fork está aberto, `MAPTOOL_MCP_ENABLED=true` estava definido ao iniciar e porta/URL coincidem? |
| Falha de autenticação | Os dois processos herdaram exatamente o mesmo `MAPTOOL_MCP_TOKEN`? |
| Ferramentas não aparecem | Confira o caminho absoluto do script, a versão do Python, o TOML e `/mcp`; reinicie o Codex após mudanças. |
| Permissão negada | Confira se esta instância entrou como mestre ou jogador e se o token pertence ao jogador conectado. |
| Movimento recusado | Consulte o estado atual, a visibilidade, os bloqueios e as restrições da sessão; tente um trecho permitido. |
| Porta já em uso | Configure outro `MAPTOOL_MCP_PORT` e a mesma porta em `MAPTOOL_MCP_URL`; reinicie os processos. |

Para desabilitar a ponte, feche o MapTool e reinicie sem `MAPTOOL_MCP_ENABLED=true`.
