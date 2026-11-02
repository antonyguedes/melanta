<p align="center">
  <img src="docs/banner.svg" alt="Melanta: o agente de IA que roda no seu Android" width="100%">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/status-em%20desenvolvimento-4A2E4B?style=for-the-badge" alt="Status: em desenvolvimento">
  <img src="https://img.shields.io/badge/Android-12%2B-2E1430?style=for-the-badge&logo=android&logoColor=D9B8DA" alt="Android 12 ou superior">
  <img src="https://img.shields.io/badge/Kotlin-Compose-2E1430?style=for-the-badge&logo=kotlin&logoColor=D9B8DA" alt="Kotlin e Jetpack Compose">
  <img src="https://img.shields.io/badge/IA-100%25%20no%20aparelho-1A1A1D?style=for-the-badge" alt="IA 100% no aparelho">
  <a href="LICENSE"><img src="https://img.shields.io/badge/licen%C3%A7a-MIT-1A1A1D?style=for-the-badge" alt="Licença MIT"></a>
</p>

<p align="center">
  <b>Peça em português. O Melanta lê a tela, decide o próximo passo e toca nos apps por você.</b><br>
  O modelo roda no próprio celular, sem servidor e sem conta.
</p>

---

## ✨ O que ele faz

Você escreve o pedido, o agente executa e você acompanha tudo por um chip na barra de status.

| Você pede | O Melanta faz |
|---|---|
| *"Põe um alarme para 7h"* | Cria o alarme no Relógio |
| *"Coloca um timer de 5 minutos"* | Inicia o timer |
| *"Desliga o wi-fi"* | Abre o painel e desliga o interruptor |
| *"Manda SMS para 11 99999-8888 dizendo oi"* | Escreve a mensagem e **pede sua confirmação** antes de enviar |
| *"Crie um contato com nome Jose e o número +55 24 98777-8990"* | Preenche o formulário e salva |
| *"Me leva até a Avenida Paulista"* | Abre a rota no Maps |
| *"Liga para 11 98765-4321"* | Abre o discador; quem completa a ligação é você |

Ele também abre apps e links, faz buscas na web, escreve no WhatsApp e por e-mail e cria eventos na agenda. Nos demais apps, navega pela tela com toques, digitação e rolagem.

## 🔒 Privacidade

- **A inferência é local.** O modelo roda no aparelho com [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM).
- **A internet serve só para baixar o modelo.** Esse é o único uso da permissão no app; depois do download, o agente funciona sem rede. Ações como busca na web ou abrir um link usam os apps do próprio celular, que acessam a internet normalmente.
- **Os logs ficam no aparelho.** Cada execução é registrada localmente, em `files/agent-logs`.

## 🛡️ Segurança

Modelos pequenos erram, inclusive em casos sensíveis: nos testes, todos falharam em cenários de pagamento. Por isso, as regras abaixo estão **no código**, e o modelo não consegue ignorá-las.

| Regra | Exemplos |
|---|---|
| ⛔ **Bloqueado sempre** | Pagamentos e compras, apps de banco, carteiras e cripto, digitar senhas, PINs ou números de cartão |
| ✋ **Pede sua confirmação** | Enviar, excluir, ligar, confirmar, sair da conta. Sem resposta em 60 s, a ação é negada |
| 🙋 **Passa a vez para você** | Janelas protegidas do sistema, como pedidos de permissão, que o agente não consegue ler |
| ⏱️ **Limites por tarefa** | 20 passos, 5 minutos, 3 repetições da mesma ação na mesma tela, 3 erros seguidos |

**Para parar na hora:** botão **PARAR** flutuante, ação na notificação ou **Volume− três vezes**.

## ⚙️ Como funciona

O agente repete um ciclo curto até concluir ou atingir um limite. O modelo sugere a ação, mas quem autoriza e executa é o código.

```mermaid
%%{init: {"theme": "base", "themeVariables": {"primaryColor": "#4A2E4B", "primaryTextColor": "#FFFFFF", "primaryBorderColor": "#D9B8DA", "lineColor": "#9B7A9C", "secondaryColor": "#2E1430", "tertiaryColor": "#1A1A1D", "fontFamily": "Inter, Segoe UI, Roboto, sans-serif"}}}%%
flowchart LR
    A["👁️ Perceber<br/>lê a tela pela Acessibilidade"] --> B["🧠 Raciocinar<br/>modelo local escolhe a ação"]
    B --> C{"🛡️ Validar<br/>SafetyPolicy"}
    C -- permitido --> D["👆 Agir<br/>toque, texto ou intent"]
    C -- sensível --> E["✋ Você confirma"]
    E -- sim --> D
    C -- proibido --> F["⛔ Encerra"]
    E -- não --> F
    D --> G["⏳ Espera a tela estabilizar"]
    G --> A
```

A tela chega ao modelo como uma lista compacta de elementos:

```text
SCREEN (com.android.settings | Settings Panel):
[1] txt "Wi‑Fi"
[3] item "Wi‑Fi" click
[4] sw "Wi‑Fi" click checked
[9] btn "Done" click
```

E ele responde com uma única ação em JSON:

```json
{"thought": "rule 5: tocar no interruptor", "tool": "click", "args": {"id": 4}}
```

## 🧠 Modelos

Todos são gratuitos, têm licença Apache-2.0 e não exigem login. O download é feito pelo app, com retomada e conferência de SHA-256.

| Modelo | Tamanho | RAM recomendada | Acertos¹ | Velocidade¹ |
|---|---:|---:|---:|---:|
| **Gemma 4 E2B** (padrão) | 2,6 GB | 8 GB | **22/28** | 42 tok/s |
| Qwen3 4B Instruct 2507 | 2,7 GB | 12 GB | 18/28 | 11 tok/s |
| Qwen2.5 1.5B (leve) | 1,6 GB | 6 GB | 8/28 | 27 tok/s |

<sub>¹ Benchmark de 28 cenários do <code>tools/model-bench</code>, rodado em CPU de desktop com 16 threads. Num celular, a velocidade será diferente.</sub>

## 📊 Estado atual

O projeto está em desenvolvimento e ainda não há versão publicada.

| Item | Resultado |
|---|---|
| Testes de ponta a ponta (10 tarefas, emulador, Gemma em CPU) | **9/10**, média de 21 s por tarefa |
| Testes unitários | 98 passando |

**Limitações conhecidas**

- **Evento na agenda:** é a tarefa que falta no E2E. O Google Agenda do emulador de teste não tem conta Google e fecha o editor.
- **Celular real:** Volume− três vezes e aceleração por GPU ainda não foram validados em um aparelho. O emulador usa só a CPU.
- **WhatsApp, e-mail e tarefas longas:** ainda não fazem parte do E2E.
- **Apps que escondem elementos:** se um app não expõe um campo à Acessibilidade, o agente não consegue lê-lo.

## 🚀 Como rodar

**Você precisa de:** Android SDK com a plataforma 37 e JDK (o projeto é desenvolvido com o JDK 27). O aparelho precisa de Android 12 ou superior e de espaço para o modelo, mais um cache de pesos de tamanho parecido.

```bash
git clone git@github.com:antonyguedes/melanta.git
cd melanta
./gradlew :app:installDebug
```

Depois, no aparelho:

1. Abra o Melanta e conceda as permissões pedidas, principalmente a **Acessibilidade**.
2. Baixe um modelo na tela de **Modelos**.
3. Escreva um pedido e acompanhe pela barra de status.

> [!TIP]
> Para rodar o modelo em um emulador, inicie-o com pelo menos 6 GB de RAM e 8 núcleos: `-memory 6144 -cores 8`.

## 🧩 Estrutura

```text
melanta
├── app                  Navegação, onboarding de permissões, tela de modelos, playground
├── core
│   ├── ai               Motor LiteRT-LM, catálogo e download de modelos, prompt, parser de ações
│   ├── automation       Serviço de Acessibilidade, leitura da tela, execução de ações e intents
│   ├── designsystem     Tema e componentes da marca
│   └── common           Utilitários compartilhados
├── feature
│   └── agent            Loop do agente, regras de segurança, overlay, notificações e tela principal
└── tools
    ├── model-bench      Benchmark de modelos no desktop, com o mesmo prompt e parser do app
    └── e2e              Testes de ponta a ponta via adb
```

**Tecnologias:** Kotlin, Jetpack Compose, Hilt, Coroutines, WorkManager e LiteRT-LM.

## 🧪 Testes

```bash
# Testes unitários e lint
./gradlew testDebugUnitTest lintDebug

# Ponta a ponta: exige o build de debug instalado, com o modelo no aparelho
python3 tools/e2e/run_e2e.py --serial emulator-5554
python3 tools/e2e/run_e2e.py --only alarme,wifi,sms

# Benchmark de um modelo no desktop
./gradlew :tools:model-bench:installDist
tools/model-bench/build/install/model-bench/bin/model-bench caminho/do/modelo.litertlm --threads 16
```

> [!NOTE]
> O roteiro de E2E procura o `adb` em `~/Android/Sdk/platform-tools/`. Ele também altera o aparelho: envia SMS, muda o Wi-Fi, apaga os contatos `Jose` e `Maria` e redefine a permissão de notificações do YouTube. Use um emulador dedicado.

## 📄 Licença

O código é distribuído sob a [licença MIT](LICENSE). © 2026 Antony Guedes.

A imagem do banner mostra Melantha, personagem de *Arknights*, e não faz parte dessa licença. Os direitos da personagem pertencem aos seus detentores.
