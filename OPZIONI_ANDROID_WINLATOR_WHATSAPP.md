# Opzioni camera ruotata — Android, Winlator e WhatsApp

Richiesta del 13 novembre 2026: poter usare Meet/Zoom sul tablet con camera corretta, eventualmente dentro Winlator, e valutare una soluzione per WhatsApp.

## 1. Android con cerchio e condivisione

È il percorso già presente in Corrected Camera: CameraX → rotazione → overlay e MJPEG. La revisione 0.3.0-test corregge il possesso della camera e la pipeline dei fotogrammi. Il cerchio può essere mostrato condividendo l'intero schermo, se il sistema/app di chiamata includono gli overlay. Non diventa la normale camera selezionabile nelle altre app Android. Evitare che la camera della chiamata e Corrected Camera contendano lo stesso sensore.

Stato: APK di prova; chiamata reale sul dispositivo ancora da verificare.

## 2. Winlator con un ponte camera

Nel repository ci sono già due basi diverse:

- `windows/CorrectedCameraWinlator`: EXE che riceve e mostra lo stream MJPEG Android; non registra una webcam virtuale.
- `windows/CorrectedCameraWinlatorFull`: acquisizione da dispositivi DirectShow, trasformazione, memoria condivisa e filtro `CorrectedCamera Virtual Camera`. Questo progetto cerca una camera già visibile a Wine; non riceve il MJPEG Android. Se Wine non espone la camera fisica, il solo EXE non può acquisirla.

Prototipo proposto: ricevere il video corretto dell'app Android, convertirlo nel formato della memoria condivisa e alimentare il filtro DirectShow già presente. Così non si presume che Winlator esponga Camera2 come camera Windows e non è necessario partire riscrivendo Winlator.

Meet/Zoom devono essere eseguiti nell'ambiente che vede quella camera Windows. Un'app Android esterna al contenitore non vede il filtro DirectShow. Restano da verificare registrazione del filtro in Wine, architettura dei processi, scelta della sorgente nella versione specifica del client/browser, latenza e sostenibilità del tablet durante una chiamata.

Stato: architettura e sorgenti esistenti esaminati; il ponte MJPEG → memoria condivisa → camera DirectShow NON è ancora implementato o provato. Nessuna nuova build Winlator consegnata con questa revisione.

## 3. WhatsApp Android con camera ruotata

Un normale APK separato non può obbligare il client ufficiale a consumare i suoi fotogrammi corretti. Non è stata creata una versione modificata di WhatsApp, né cambiato il sistema del tablet. Rifare solo grafica o contenitore dell'app non risolve l'accesso alla camera.

Prima verifica: modello/firmware, Android e versione WhatsApp; se il destinatario vede realmente un'immagine a testa in giù oppure se è solo specchiata l'anteprima; se il difetto compare anche nelle altre app fotocamera. Le eventuali strade HAL/UVC/root o modifiche al client sono progetti distinti e non vanno promesse come una semplice opzione di questo APK.

## Ordine proposto

1. Provare stabilità del cerchio Android senza un'altra app che cattura lo stesso sensore.
2. Provare una chiamata con condivisione dell'intero schermo e corretta separazione delle camere.
3. Se serve una vera sorgente selezionabile nelle app Windows, costruire e collaudare il ponte MJPEG/DirectShow nel Winlator esistente.
4. Valutare WhatsApp con dati reali sul difetto di orientamento e sulla piattaforma; compatibilità di WhatsApp Windows dentro Winlator non verificata.

Queste alternative sono registrate come possibilità richieste, non come funzioni già disponibili.
