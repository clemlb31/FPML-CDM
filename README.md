# Interface Generation

## Prérequis : démarrer LMStudio

LMStudio doit être en cours d'exécution avant de lancer l'agent.

### Démarrer le serveur LMStudio (CLI)

```powershell
# Démarrer le serveur local LMStudio (port 1234 par défaut)
lms server start

# Charger un modèle spécifique
lms load qwen/qwen3.5-9b

# Vérifier l'état du serveur
lms status

# Arrêter le serveur
lms server stop
```

### Ou via l'interface graphique

```powershell
Start-Process "C:\Users\$env:USERNAME\AppData\Local\Programs\LM Studio\LM Studio.exe"
```

---

## Lancer l'agent

```powershell
# Activer l'environnement virtuel
.\.venv\Scripts\Activate.ps1

# Lancer l'agent
python .\agent\run_agent.py
```
