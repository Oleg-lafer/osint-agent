# Running Leadspotting

Run these commands from the repository root in PowerShell.

## 1. PreProcessing pipeline

Process a chosen number of the newest MySQL posts. For example, process 50 posts:

```powershell
& '.\PreProcessing pipeline.ps1' 50
```

Replace `50` with the required post count. This command uses paid OpenAI calls and writes
pipeline results, including clusters, to MySQL. Run it only when preprocessing is wanted.

## 2. Live chat

Start the database-only Java backend and React frontend:

```powershell
& '.\live chat.ps1'
```

Live chat does not run preprocessing or fill pipeline tables. It requires an existing completed
preprocessing run; otherwise it stops with `No completed summarized pipeline run exists`.
Press Ctrl+C to stop the backend and frontend.
