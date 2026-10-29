# Leve Launcher TV

Tela inicial só de texto para Android TV / TV Box. Sem ícones, sem imagens, sem nada rodando em segundo plano.

## O que tem
- Lista de apps em texto, fundo preto, relógio pequeno no canto
- Segurar OK (ou tecla Menu) num app: **Fixar no topo**, **Ocultar**, **Info do app**
- Linha **Configurações** no final e opção para mostrar os apps ocultos
- Voltar na tela inicial leva ao topo da lista

## Compilar (GitHub Actions)
1. Crie um repositório no GitHub e envie todos estes arquivos (incluindo a pasta `.github`).
2. A aba **Actions** compila sozinha. Ao terminar, baixe o artefato **LeveLauncherTV** (vem um .zip com o APK dentro).

## Link direto do APK (sempre a última versão)
https://github.com/Gabrielbatman266/Leve-laucher-tv/releases/latest/download/LeveLauncherTV.apk

## Instalar no TV Box
1. No TV Box, abra o app **Downloader** e cole o link acima. Ou coloque o APK num pendrive.
2. Abra com um gerenciador de arquivos e permita "fontes desconhecidas" quando pedir.
3. Aperte o botão Home do controle e escolha **Leve Launcher TV → Sempre**.
   - Se o box não perguntar: Configurações → Apps → Apps padrão → Tela inicial.

## Atualizar
No próprio launcher, desça até **⬇ Atualizar**. Ele confere se há versão nova aqui no GitHub, baixa e abre o instalador.

Para isso funcionar, o repositório precisa de dois Secrets (Settings → Secrets and variables → Actions):
`KEYSTORE_B64` e `KEYSTORE_PASS`. Eles mantêm a mesma assinatura em todas as versões.
