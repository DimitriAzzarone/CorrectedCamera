# Corrected Camera — test stabilità 0.3.0

Variante di prova separata dalla 0.2.0: nome launcher **Corrected Camera Test**, identificativo `ch.formazione.correctedcamera.stability`. Non sostituisce né cancella l'app originale. Le modifiche sono nel ramo `fix/camera-stream-stability`; i componenti Windows restano invariati.

Sintomo segnalato: l'utente continua a vedere la studentessa, ma lei perde il suo video, che si blocca o diventa nero durante la chiamata/condivisione. Non è stato riprodotto il problema su quel tablet: non si dichiara risolta una chiamata reale sulla base della sola compilazione.

## Correzioni verificabili

- Rotazione e controlli della finestra flottante non riaprono la camera nell'Activity né chiamano più `unbindAll()` sul servizio. Ogni componente rilascia solo i propri use case; callback tardive vengono scartate.
- Il passaggio al servizio camera avviene con l'Activity ancora visibile. L'app va in background solo dopo il primo fotogramma, evitando di avviare un servizio camera quando i permessi “durante l'uso” non sono più disponibili.
- Conversione RGBA con stride e padding controllati, anche quando manca il padding nell'ultima riga. Canali convertiti esplicitamente in ARGB, senza assumere il byte order nativo del bitmap.
- Un solo fotogramma in attesa di essere mostrato. I bitmap già affidati all'ImageView non vengono riciclati manualmente mentre RenderThread può ancora usarli.
- Risoluzione di analisi preferita 640×480, massimo circa 15 aggiornamenti/s prima della conversione; JPEG di stream limitato a 480 px sul lato lungo. Ridotto il lavoro che può accumularsi e scaldare il dispositivo.
- Errori dell'analyzer intercettati e fotogrammi CameraX sempre chiusi. Stato “camera occupata”/“video fermo” mostrato anziché lasciarlo senza spiegazione. Nessun tentativo aggressivo di sottrarre la camera a un'altra app.
- Pulsante **Ferma fotocamera** nell'app e comando di arresto nella notifica del servizio, se le notifiche sono autorizzate. Il servizio non riparte da solo con camera/rotazione predefinite dopo un arresto del processo.
- Callback MediaProjection registrata prima della creazione del display virtuale, richiesta da Android recenti; arresto della registrazione gestito quando Android revoca la proiezione.

## Come provare senza due app in conflitto

1. Prima della prova, usare **Impostazioni Android → App → Corrected Camera originale → Arresto forzato**. Chiudere la schermata o scacciarla dai recenti potrebbe lasciare attivo il vecchio servizio camera. Nessun dato viene cancellato dall'arresto.
2. Installare e aprire **Corrected Camera Test**; concedere camera e sovrapposizione alle altre app.
3. Verificare prima il cerchio fuori dalla chiamata, poi tornare nell'app, ruotare/cambiare camera e riaprire il cerchio: questo percorso prima poteva sganciare la camera dal servizio.
4. Se si vuole mostrare il cerchio attraverso una condivisione, scegliere la condivisione dell'**intero schermo**, dove consentita. La condivisione della sola app può escludere le finestre di un'altra app.
5. Se il servizio di chiamata usa la stessa fotocamera fisica, può contenderla a Corrected Camera. In una prova con cerchio condiviso, disattivare **la propria** videocamera nella chiamata mantenendo la ricezione della studentessa; Corrected Camera resta l'unico produttore della camera. Se invece si usa la normale videocamera della chiamata, fermare Corrected Camera.
6. Annotare se il blocco riguarda già il cerchio sul tablet o soltanto l'immagine ricevuta dall'altra persona, e l'eventuale messaggio di errore. Questo distingue cattura, condivisione e trasporto della chiamata.

Non avviare la registrazione schermo interna solo per condividere nella chiamata: sono due acquisizioni MediaProjection diverse e Android può terminarne una quando parte l'altra.

## WhatsApp e limiti Android

Questa app produce un overlay e uno stream MJPEG (`http://IP_TABLET:8080/video`), **non una fotocamera virtuale Android selezionabile da WhatsApp**. Un APK normale non può imporre a WhatsApp i propri fotogrammi ruotati o cambiare la trasformazione interna del client ufficiale. Nessuna versione modificata di WhatsApp è inclusa.

La rotazione della normale videocamera WhatsApp richiede una diagnosi separata del tablet, dell'orientamento sensore e della versione dell'app. Le strade che coinvolgono camera HAL/UVC/root o modifiche di sistema restano fuori da questa patch. Un video a testa in giù e un'anteprima soltanto specchiata non sono lo stesso difetto.

## Verifica e build

JDK 17, Gradle 8.9, SDK 35, Kotlin 2.0.21 e CameraX 1.5.3.

```
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

GitHub Actions deve risultare verde, con step **Build debug APK** riuscito e artifact **CorrectedCamera-debug** presente. I test JVM verificano buffer/stride/canali/limiti e gestione della coda; Robolectric con grafica nativa verifica pixel, rotazione e mancato riciclo anticipato dei bitmap visualizzati. Non simulano una fotocamera fisica, il firmware del tablet, termica o una chiamata WhatsApp.

Stato, decisioni e questioni aperte: [STATO_PROGETTO.md](STATO_PROGETTO.md).
