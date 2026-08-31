# SpecOps

[![CI](https://github.com/rawatprince/SpecOps/actions/workflows/ci.yml/badge.svg)](https://github.com/rawatprince/SpecOps/actions/workflows/ci.yml)
[![BApp Store](https://img.shields.io/badge/BApp%20Store-available-orange)](https://portswigger.net/bappstore/a7244f1092db4db3a609e4c306307312)
![Java](https://img.shields.io/badge/java-21%2B-blue)
![Burp](https://img.shields.io/badge/Burp-2025.7%2B-orange)
![License](https://img.shields.io/github/license/rawatprince/SpecOps?cacheSeconds=300)
![Release](https://img.shields.io/github/v/release/rawatprince/SpecOps?display_name=tag&sort=semver)

**SpecOps** is a Burp Suite extension that ingests an OpenAPI or Swagger spec and instantly builds a workbench to test every documented endpoint.

Available in the official **[BApp Store](https://portswigger.net/bappstore/a7244f1092db4db3a609e4c306307312)** - install it directly from inside Burp Suite.

![SpecOpsDemo](https://github.com/rawatprince/SpecOps/wiki/images/SpecOpsDemo.gif)

## Features

- Import specs from file, URL, or paste
- Global parameter store with import or export, proxy auto fill, and value generation
- Auth profiles for API keys, Bearer or JWT, Basic, OAuth2
- Custom header rules with scopes and overwrite control
- Endpoints workbench with preview, bulk ping, Repeater or Intruder send
- Attack results with request and response viewers
- Multi server mode to hit every server defined in the spec

See the full **[Wiki Guide](../../wiki)** for screenshots and detailed usage.

## Installation

### Prerequisites
- Burp Suite **2025.7+** (for Montoya API support).
- Java **21+**.

### Method 1: BApp Store (Recommended)
1. In Burp Suite, go to the `Extensions` → `BApp Store` tab.
2. Search for **SpecOps**.
3. Click `Install`.

A new **SpecOps** tab will appear, and Burp will keep the extension up to date automatically.
You can also view the listing on the web: [SpecOps on the BApp Store](https://portswigger.net/bappstore/a7244f1092db4db3a609e4c306307312).

### Method 2: Prebuilt Release (manual .jar)
1. Download the latest `SpecOps-vX.Y.Z.jar` from the [**Releases**](https://github.com/rawatprince/SpecOps/releases) page.
2. In Burp Suite, go to the `Extender` → `Extensions` tab.
3. Click `Add`, select the downloaded `SpecOps-vX.Y.Z.jar` file, and ensure the extension type is set to `Java`.
4. A new **SpecOps** tab will appear in Burp's main window.

### Method 3: Build from Source
1. Clone the repository:
   ```bash
   git clone https://github.com/rawatprince/SpecOps.git
   cd SpecOps
   ```
2. Build the project using Gradle:
   ```bash
   ./gradlew clean test shadowJar
   ```

## Why SpecOps?
SpecOps turns static API specifications into a **dynamic attack surface** - removing the friction of manual request building and letting you focus on testing.  
With its parameter store, auth handling, custom headers, and bulk testing features, SpecOps makes API pentesting **faster, consistent, and far more powerful**.

---

## Links

- **BApp Store**: [SpecOps on the PortSwigger BApp Store](https://portswigger.net/bappstore/a7244f1092db4db3a609e4c306307312)
- **Website**: [https://princerawat.com](https://princerawat.com)
- **User's manual**: [SpecOps Wiki](https://github.com/rawatprince/SpecOps/wiki)
- **Screenshots**: [View Screenshots](https://github.com/rawatprince/SpecOps/wiki/Screenshots)
- **Releases**: [Download SpecOps](https://github.com/rawatprince/SpecOps/releases)
- **Commits feed (RSS/Atom)**: [SpecOps commits](https://github.com/rawatprince/SpecOps/commits/main.atom)
- **X (Twitter)**: [@_princerawat](https://x.com/_princerawat)
- **LinkedIn**: [Prince Rawat](https://www.linkedin.com/in/princerawat/)

---

If you like **SpecOps**, please consider giving it a ⭐ on GitHub, it helps others discover the project!  

Welcoming contributions from everyone:  
- Open [issues](https://github.com/rawatprince/SpecOps/issues) for bugs or feature requests  
- Submit pull requests to improve the project  
- Share ideas or suggestions  
