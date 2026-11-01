# Configuração ao vivo do Leve Launcher TV

O launcher relê `leve.json` a cada minuto (com a tela inicial aberta) e aplica sem reinstalar.
Mudar este arquivo **não** gera versão nova do app.

| Campo | O que faz | Exemplo |
|---|---|---|
| `title` | Texto do canto de cima | `"LEVE"` |
| `message` | Aviso ao lado do título (dourado) | `"Filme novo no YouCine!"` |
| `accent` | Cor da seleção | `"#FF6BD5"` |
| `sky` | 3 cores do fundo (cima → baixo) | `["#000000", "#1A0630", "#3A0A5A"]` |
| `stars` | Liga/desliga as estrelas (se você nunca mexeu nisso pela TV) | `true` |
| `clockSize` | Tamanho do relógio | `90` |
| `pin` | Apps para colocar nos principais (aplica 1x por `rev`) | `["com.netflix.ninja"]` |
| `hide` | Apps para ocultar (aplica 1x por `rev`) | `["com.android.vending"]` |
| `rename` | Nome que aparece para um app | `{"com.xyz": "Filmes"}` |
| `shortcuts` | Atalhos grandes para sites ou apps | `[{"label": "Clima", "url": "https://..."}]` |
| `rev` | Aumente quando mudar `pin`/`hide` para aplicar de novo | `2` |
