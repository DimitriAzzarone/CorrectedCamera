
# CorrectedCamera per Winlator — build completa

Questa versione NON usa lo stream Android.

Funzioni implementate:
- enumera le webcam DirectShow visibili in Winlator/Wine;
- acquisisce la camera fisica;
- anteprima;
- rotazione 0/90/180/270;
- cambio camera;
- pubblicazione dei frame corretti tramite memoria condivisa;
- sorgente DirectShow `CorrectedCamera Virtual Camera`;
- setup e uninstall.

Il Setup copia i file in:
`%LOCALAPPDATA%\CorrectedCamera`

Poi registra la virtual camera per l'utente corrente.

## Limite reale
Se Winlator/Wine non espone nessuna camera fisica come dispositivo DirectShow,
il programma mostrerà chiaramente "Nessuna camera DirectShow visibile".
Nessun EXE Windows può accedere alla Camera2 Android se Wine non la espone.
