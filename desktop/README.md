# LanClip Share Desktop (macOS `.dmg` & Linux `.flatpak`)

Versão Desktop do **LanClip Share** 100% compatível com o aplicativo Android (`v1.0.1`) instalado no seu **Tablet Xiaomi** e **Celular Motorola**.

---

## 📦 Pacotes Gerados para Download

- **macOS (`.dmg`)**:
  - [`LanClipShare-macOS-v1.0.1.dmg`](./LanClipShare-macOS-v1.0.1.dmg)
- **Linux (`.flatpak` Bundle)**:
  - [`LanClipShare-Linux-v1.0.1.flatpak`](./LanClipShare-Linux-v1.0.1.flatpak)
- **Script Universal Portátil (macOS / Linux)**:
  - [`src/lanclip_desktop.py`](./src/lanclip_desktop.py)

---

## 🍎 Como Instalar no macOS (`.dmg`)

1. Baixe e dê dois cliques em **[`LanClipShare-macOS-v1.0.1.dmg`](./LanClipShare-macOS-v1.0.1.dmg)** para montar o disco.
2. Arraste o aplicativo **`LanClipShare.app`** para a pasta **`Applications`**.
3. Na primeira vez que abrir no macOS (por não ser assinado com certificado pago da Apple Store):
   - Clique com o **botão direito** (ou `Control + Clique`) sobre o **`LanClipShare.app`** → selecione **Abrir**.
   - Caso o macOS Sequoia/Sonoma bloqueie por quarentena de download, rode no Terminal:
     ```bash
     xattr -cr /Applications/LanClipShare.app
     ```
4. Permita o acesso à **Rede Local** quando solicitado. O seu Mac aparecerá automaticamente na barra superior do seu Xiaomi e Motorola!

---

## 🐧 Como Instalar no Linux (`.flatpak`)

1. Baixe o pacote **[`LanClipShare-Linux-v1.0.1.flatpak`](./LanClipShare-Linux-v1.0.1.flatpak)**.
2. Instale com 1 comando no terminal (ou dando dois cliques na GNOME Software / KDE Discover):
   ```bash
   flatpak install --user LanClipShare-Linux-v1.0.1.flatpak
   ```
3. Execute pelo menu de aplicativos do sistema (**LanClip Share**) ou pelo terminal:
   ```bash
   flatpak run com.lanclip.Share
   ```
   *(Opcional: Para rodar diretamente sem Flatpak em qualquer distribuição Linux, basta executar `python3 src/lanclip_desktop.py`.)*

---

## 🔄 Recursos e Compatibilidade de Rede

- **Mesmo protocolo do Android**:
  - **UDP Broadcast (`45454`)**: descobre automaticamente o Xiaomi e o Motorola na mesma rede Wi-Fi/LAN.
  - **TCP Streaming (`45455`)**: envia e recebe textos do Clipboard e arquivos de qualquer tamanho (Fotos, Planilhas, PDFs, ZIPs, `.APK`), salvando em `~/Downloads/LanClipShare`.
- **Integração Nativa com o Clipboard**:
  - **macOS**: lê e escreve via `pbcopy` / `pbpaste`.
  - **Linux**: lê e escreve via `wl-copy` / `wl-paste` (Wayland) e `xclip` / `xsel` (X11).
