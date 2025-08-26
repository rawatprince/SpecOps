# SpecOps

[![CI](https://github.com/rawatprince/SpecOps/actions/workflows/ci.yml/badge.svg)](https://github.com/rawatprince/SpecOps/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/java-17%2B-blue)
![Burp](https://img.shields.io/badge/Burp-2023.1%2B-orange)
![License](https://img.shields.io/github/license/rawatprince/SpecOps)
![Release](https://img.shields.io/github/v/release/rawatprince/SpecOps)

**SpecOps** is a Burp Suite extension that ingests an OpenAPI or Swagger spec and instantly builds a workbench to test every documented endpoint.

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
- Burp Suite **2023.1+** (for Montoya API support).
- Java **17+**.

### Method 1: Prebuilt Release (Recommended)
1. Download the latest `SpecOps-vX.Y.Z.jar` from the [**Releases**](https://github.com/rawatprince/SpecOps/releases) page.
2. In Burp Suite, go to the `Extender` → `Extensions` tab.
3. Click `Add`, select the downloaded `SpecOps-vX.Y.Z.jar` file, and ensure the extension type is set to `Java`.
4. A new **SpecOps** tab will appear in Burp's main window.

### Method 2: Build from Source
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