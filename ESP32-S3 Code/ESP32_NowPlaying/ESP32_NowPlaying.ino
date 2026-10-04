/*
 * ESP32-S3 Now Playing Display
 *
 * Board: ESP32-S3 Zero N8R8
 * Display: GC9A01 240x240
 * Touch: CST816S
 *
 * Libraries:
 * - LVGL 8.3.11
 * - Arduino_GFX
 * - NimBLE-Arduino 2.x
 * - ArduinoJson 7.x
 */

#define LV_CONF_INCLUDE_SIMPLE

#include <lvgl.h>
#include <Arduino_GFX_Library.h>
#include <Wire.h>
#include <NimBLEDevice.h>
#include <ArduinoJson.h>
#include <esp_heap_caps.h>

#include "config.h"

// --------------------------------------------------
// BLE CONFIGURATION
// --------------------------------------------------

static const char *DEVICE_NAME = "NowPlaying-S3";

static const char *SERVICE_UUID =
    "8d3f0001-7b3a-4f2a-9c1d-6e5a4b3c2d10";

static const char *META_UUID =
    "8d3f0002-7b3a-4f2a-9c1d-6e5a4b3c2d10";

static const char *ART_UUID =
    "8d3f0003-7b3a-4f2a-9c1d-6e5a4b3c2d10";

static const char *CMD_UUID =
    "8d3f0004-7b3a-4f2a-9c1d-6e5a4b3c2d10";

#define TP_ADDR 0x15

// --------------------------------------------------
// ALBUM ART CONFIGURATION
// --------------------------------------------------

static const uint16_t ART_W = 240;
static const uint16_t ART_H = 240;

static const size_t ART_BYTES = ART_W * ART_H * 2;

static const size_t ART_MAX_CHUNK_DATA = 234;

// --------------------------------------------------
// DISPLAY
// --------------------------------------------------

Arduino_DataBus *bus = new Arduino_ESP32SPI(
    TFT_DC,
    TFT_CS,
    TFT_SCLK,
    TFT_MOSI,
    GFX_NOT_DEFINED,
    SPI_MODE0
);

Arduino_GFX *gfx = new Arduino_GC9A01(
    bus,
    TFT_RST,
    0,
    true
);

// --------------------------------------------------
// LVGL
// --------------------------------------------------

static lv_disp_draw_buf_t draw_buf;
static lv_color_t *draw_pixels = nullptr;

static lv_disp_drv_t disp_drv;
static lv_indev_drv_t indev_drv;

// --------------------------------------------------
// BLE CHARACTERISTICS
// --------------------------------------------------

NimBLECharacteristic *metaCharacteristic = nullptr;
NimBLECharacteristic *artCharacteristic = nullptr;
NimBLECharacteristic *commandCharacteristic = nullptr;

// --------------------------------------------------
// LVGL OBJECTS
// --------------------------------------------------

static lv_obj_t *screenObj;
static lv_obj_t *connectionLabel;

static lv_obj_t *artImage;
static lv_obj_t *titleLabel;
static lv_obj_t *artistLabel;

static lv_obj_t *progressSlider;
static lv_obj_t *elapsedLabel;
static lv_obj_t *durationLabel;

static lv_obj_t *playButtonLabel;


// --------------------------------------------------
// ALBUM ART BUFFERS
// --------------------------------------------------

static lv_img_dsc_t artDescriptor;

static uint8_t *artBuffer = nullptr;
static uint8_t *displayArtBuffer = nullptr;

static uint16_t artExpectedChunks = 0;
static uint16_t artReceivedChunks = 0;

static bool artChunkSeen[1400] = {false};

static uint16_t artPacketDataSize = 0;

static uint16_t artTrackId = 0;
static uint16_t artTransferId = 0;
static bool artTransferActive = false;

// --------------------------------------------------
// METADATA
// --------------------------------------------------

static uint8_t metaBuffer[512];

static uint16_t metaExpectedChunks = 0;
static uint16_t metaReceivedChunks = 0;
static uint16_t metaPacketDataSize = 0;

static size_t metaBytesReceived = 0;

static uint16_t metaTrackId = 0;
static uint16_t metaTransferId = 0;
static bool metaTransferActive = false;

static uint16_t activeTrackId = 0;

static uint32_t trackDurationMs = 0;
static uint32_t playbackPositionMs = 0;

static bool isPlaying = false;
static bool bleConnected = false;

static bool sliderBeingDragged = false;
// --------------------------------------------------
// UI FLAGS
// --------------------------------------------------

static volatile bool uiMetaPending = false;
static volatile bool uiArtPending = false;
static volatile bool uiConnectionPending = false;
static volatile bool uiTrackChanged = false;

static char pendingTitle[128] = "Nothing playing";
static char pendingArtist[128] = "Connect your Android tablet";

// --------------------------------------------------
// TIMERS
// --------------------------------------------------

static uint32_t lastLvTick = 0;
static uint32_t lastProgressUpdate = 0;
static uint32_t lastTouchPoll = 0;

// --------------------------------------------------
// UTILITY FUNCTIONS
// --------------------------------------------------

static void formatTime(uint32_t ms, char *out, size_t n) {

    uint32_t seconds = ms / 1000;

    snprintf(
        out,
        n,
        "%lu:%02lu",
        (unsigned long)(seconds / 60),
        (unsigned long)(seconds % 60)
    );
}

// --------------------------------------------------
// PROGRESS UI
// --------------------------------------------------

static void updateProgressUI() {

    if (!progressSlider || sliderBeingDragged)
        return;

    uint32_t pos = playbackPositionMs;

    if (isPlaying && trackDurationMs > 0) {

        uint32_t now = millis();

        pos += now - lastProgressUpdate;

        if (pos > trackDurationMs)
            pos = trackDurationMs;
    }

    lv_slider_set_range(
        progressSlider,
        0,
        trackDurationMs > 0 ? trackDurationMs : 1
    );

    lv_slider_set_value(
        progressSlider,
        pos,
        LV_ANIM_OFF
    );

    char t[16];

    formatTime(pos, t, sizeof(t));

    lv_label_set_text(elapsedLabel, t);
}

// --------------------------------------------------
// PLAY / PAUSE ICON
// --------------------------------------------------

static void updatePlayGlyph() {

    if (playButtonLabel) {

        lv_label_set_text(
            playButtonLabel,
            isPlaying ? LV_SYMBOL_PAUSE : LV_SYMBOL_PLAY
        );
    }
}

// --------------------------------------------------
// BLUETOOTH STATUS
// --------------------------------------------------

static void updateConnectionUI() {

    if (!connectionLabel)
        return;

    lv_label_set_text(
        connectionLabel,
        bleConnected
            ? LV_SYMBOL_BLUETOOTH " CONNECTED"
            : LV_SYMBOL_BLUETOOTH " DISCONNECTED"
    );

    lv_obj_set_style_text_color(
        connectionLabel,
        bleConnected
            ? lv_color_hex(0x55DDAA)
            : lv_color_hex(0x888888),
        0
    );
}

// --------------------------------------------------
// SEND COMMAND TO ANDROID
// --------------------------------------------------

static void sendCommand(const char *command) {

    if (!bleConnected || !commandCharacteristic)
        return;

    commandCharacteristic->setValue(
        (uint8_t *)command,
        strlen(command)
    );

    commandCharacteristic->notify();
}

// --------------------------------------------------
// BUTTON EVENTS
// --------------------------------------------------

static void previousEvent(lv_event_t *e) {

    if (lv_event_get_code(e) == LV_EVENT_CLICKED)
        sendCommand("PREV");
}

static void nextEvent(lv_event_t *e) {

    if (lv_event_get_code(e) == LV_EVENT_CLICKED)
        sendCommand("NEXT");
}

static void playEvent(lv_event_t *e) {

    if (lv_event_get_code(e) == LV_EVENT_CLICKED)
        sendCommand("PLAY");
}

// --------------------------------------------------
// PROGRESS SLIDER EVENT
// --------------------------------------------------

static void sliderEvent(lv_event_t *e) {

    lv_event_code_t code = lv_event_get_code(e);

    if (code == LV_EVENT_PRESSED) {

        sliderBeingDragged = true;
    }

    if (
        code == LV_EVENT_RELEASED ||
        code == LV_EVENT_PRESS_LOST
    ) {

        uint32_t value = lv_slider_get_value(progressSlider);

        char cmd[32];

        snprintf(
            cmd,
            sizeof(cmd),
            "SEEK:%lu",
            (unsigned long)value
        );

        sendCommand(cmd);

        playbackPositionMs = value;
        lastProgressUpdate = millis();

        sliderBeingDragged = false;

        updateProgressUI();
    }
}

// --------------------------------------------------
// CREATE BUTTON
// --------------------------------------------------

static lv_obj_t *makeButton(
    lv_obj_t *parent,
    const char *glyph,
    lv_event_cb_t callback,
    int width,
    int height
) {

    lv_obj_t *btn = lv_btn_create(parent);

    lv_obj_set_size(btn, width, height);

    lv_obj_set_style_radius(
        btn,
        LV_RADIUS_CIRCLE,
        0
    );

    lv_obj_set_style_bg_color(
        btn,
        lv_color_hex(0x242832),
        0
    );

    lv_obj_set_style_bg_color(
        btn,
        lv_color_hex(0x3A4050),
        LV_STATE_PRESSED
    );

    lv_obj_t *label = lv_label_create(btn);

    lv_label_set_text(label, glyph);

    lv_obj_center(label);

    lv_obj_add_event_cb(
        btn,
        callback,
        LV_EVENT_CLICKED,
        nullptr
    );

    return btn;
}

// --------------------------------------------------
// BUILD MUSIC PLAYER UI
// --------------------------------------------------

static void buildUI() {

    screenObj = lv_scr_act();

    lv_obj_set_style_bg_color(
        screenObj,
        lv_color_hex(0x090B10),
        0
    );

    lv_obj_set_style_bg_opa(
        screenObj,
        LV_OPA_COVER,
        0
    );

    // Bluetooth status

    connectionLabel = lv_label_create(screenObj);

    lv_obj_set_width(connectionLabel, 190);

    lv_obj_align(
        connectionLabel,
        LV_ALIGN_TOP_MID,
        0,
        9
    );

    lv_obj_set_style_text_font(
        connectionLabel,
        &lv_font_montserrat_10,
        0
    );

    lv_label_set_long_mode(
        connectionLabel,
        LV_LABEL_LONG_DOT
    );

    lv_obj_set_style_text_align(
        connectionLabel,
        LV_TEXT_ALIGN_CENTER,
        0
    );

    updateConnectionUI();

    // Full-screen album artwork background.
    // Scale the 96x96 artwork to 240x240 and center it.
    // UI elements created below remain layered above the image.

    artImage = lv_img_create(screenObj);

    lv_obj_center(artImage);
    lv_img_set_zoom(artImage, 256);  // Native 240x240 artwork; no scaling

    // Song title

    titleLabel = lv_label_create(screenObj);

    lv_obj_set_width(titleLabel, 190);

    lv_obj_align(
        titleLabel,
        LV_ALIGN_TOP_MID,
        0,
        128
    );

    lv_label_set_long_mode(
        titleLabel,
        LV_LABEL_LONG_SCROLL_CIRCULAR
    );

    lv_obj_set_style_text_align(
        titleLabel,
        LV_TEXT_ALIGN_CENTER,
        0
    );

    lv_obj_set_style_text_color(
        titleLabel,
        lv_color_white(),
        0
    );

    lv_obj_set_style_text_font(
        titleLabel,
        &lv_font_montserrat_14,
        0
    );

    lv_label_set_text(
        titleLabel,
        "Nothing playing"
    );

    // Artist

    artistLabel = lv_label_create(screenObj);

    lv_obj_set_width(artistLabel, 190);

    lv_obj_align(
        artistLabel,
        LV_ALIGN_TOP_MID,
        0,
        146
    );

    lv_label_set_long_mode(
        artistLabel,
        LV_LABEL_LONG_DOT
    );

    lv_obj_set_style_text_align(
        artistLabel,
        LV_TEXT_ALIGN_CENTER,
        0
    );

    lv_obj_set_style_text_color(
        artistLabel,
        // lv_color_hex(0xA4A9B5),
        lv_color_white(),
        0
    );

    lv_obj_set_style_text_font(
        artistLabel,
        &lv_font_montserrat_10,
        0
    );

    lv_label_set_text(
        artistLabel,
        "Connect your Android tablet"
    );

    // Playback progress slider

    progressSlider = lv_slider_create(screenObj);

    lv_obj_set_size(
        progressSlider,
        166,
        7
    );

    lv_obj_align(
        progressSlider,
        LV_ALIGN_TOP_MID,
        0,
        174
    );

    lv_obj_set_style_bg_color(
        progressSlider,
        lv_color_hex(0x333844),
        LV_PART_MAIN
    );

    lv_obj_set_style_bg_color(
        progressSlider,
        lv_color_hex(0x55DDAA),
        LV_PART_INDICATOR
    );

    lv_obj_set_style_bg_color(
        progressSlider,
        lv_color_white(),
        LV_PART_KNOB
    );

    lv_obj_add_event_cb(
        progressSlider,
        sliderEvent,
        LV_EVENT_ALL,
        nullptr
    );

    // Elapsed time

    elapsedLabel = lv_label_create(screenObj);

    lv_obj_align(
        elapsedLabel,
        LV_ALIGN_TOP_LEFT,
        36,
        185
    );

    lv_obj_set_style_text_color(
        elapsedLabel,
        lv_color_hex(0xA4A9B5),
        0
    );

    lv_obj_set_style_text_font(
        elapsedLabel,
        &lv_font_montserrat_10,
        0
    );

    lv_label_set_text(elapsedLabel, "0:00");

    // Total duration

    durationLabel = lv_label_create(screenObj);

    lv_obj_align(
        durationLabel,
        LV_ALIGN_TOP_RIGHT,
        -36,
        185
    );

    lv_obj_set_style_text_color(
        durationLabel,
        lv_color_hex(0xA4A9B5),
        0
    );

    lv_obj_set_style_text_font(
        durationLabel,
        &lv_font_montserrat_10,
        0
    );

    lv_label_set_text(durationLabel, "0:00");

    // Previous button

    lv_obj_t *prev = makeButton(
        screenObj,
        LV_SYMBOL_PREV,
        previousEvent,
        34,
        34
    );

    lv_obj_align(
        prev,
        LV_ALIGN_BOTTOM_MID,
        -55,
        -8
    );

    // Play / pause button

    lv_obj_t *play = lv_btn_create(screenObj);

    lv_obj_set_size(play, 42, 42);

    lv_obj_align(
        play,
        LV_ALIGN_BOTTOM_MID,
        0,
        -5
    );

    lv_obj_set_style_radius(
        play,
        LV_RADIUS_CIRCLE,
        0
    );

    lv_obj_set_style_bg_color(
        play,
        lv_color_hex(0x55DDAA),
        0
    );

    lv_obj_add_event_cb(
        play,
        playEvent,
        LV_EVENT_CLICKED,
        nullptr
    );

    playButtonLabel = lv_label_create(play);

    lv_obj_center(playButtonLabel);

    updatePlayGlyph();

    lv_obj_set_style_text_color(
        playButtonLabel,
        lv_color_hex(0x08110E),
        0
    );

    // Next button

    lv_obj_t *next = makeButton(
        screenObj,
        LV_SYMBOL_NEXT,
        nextEvent,
        34,
        34
    );

    lv_obj_align(
        next,
        LV_ALIGN_BOTTOM_MID,
        55,
        -8
    );
}

// --------------------------------------------------
// CST816S TOUCH
// --------------------------------------------------

static bool readTouch(uint16_t &x, uint16_t &y) {

    Wire.beginTransmission(TP_ADDR);

    Wire.write(0x02);

    if (Wire.endTransmission(false) != 0)
        return false;

    if (Wire.requestFrom(TP_ADDR, (uint8_t)5) != 5)
        return false;

    uint8_t fingers = Wire.read();

    uint8_t xh = Wire.read();
    uint8_t xl = Wire.read();

    uint8_t yh = Wire.read();
    uint8_t yl = Wire.read();

    if (fingers == 0)
        return false;

    uint16_t rawX = ((xh & 0x0F) << 8) | xl;
    uint16_t rawY = ((yh & 0x0F) << 8) | yl;

    // Existing smartwatch touch calibration

    long mx = map(rawX, 16, 213, 0, 239);
    long my = map(rawY, 50, 192, 0, 239);

    x = constrain(mx, 0, 239);
    y = constrain(my, 0, 239);

    return true;
}

// --------------------------------------------------
// LVGL TOUCH CALLBACK
// --------------------------------------------------

static void touchRead(
    lv_indev_drv_t *drv,
    lv_indev_data_t *data
) {

    (void)drv;

    uint16_t x, y;

    if (millis() - lastTouchPoll < 12) {

        data->state = LV_INDEV_STATE_REL;
        return;
    }

    lastTouchPoll = millis();

    if (readTouch(x, y)) {

        data->state = LV_INDEV_STATE_PR;

        data->point.x = x;
        data->point.y = y;

    } else {

        data->state = LV_INDEV_STATE_REL;
    }
}

// --------------------------------------------------
// BLE SERVER CALLBACKS
// --------------------------------------------------

class ServerCallbacks : public NimBLEServerCallbacks {

    void onConnect(
        NimBLEServer *server,
        NimBLEConnInfo &info
    ) override {
        Serial.println("BLE CLIENT CONNECTED");
        bleConnected = true;

        uiConnectionPending = true;
    }

    void onDisconnect(
        NimBLEServer *server,
        NimBLEConnInfo &info,
        int reason
    ) override {
        Serial.println("BLE CLIENT DISCONNECTED");
        bleConnected = false;

        uiConnectionPending = true;

        NimBLEDevice::startAdvertising();
    }
};

// --------------------------------------------------
// METADATA BLE CALLBACK
// --------------------------------------------------


class MetaCallbacks : public NimBLECharacteristicCallbacks {

    void onWrite(
        NimBLECharacteristic *c,
        NimBLEConnInfo &info
    ) override {

        std::string value = c->getValue();

        // New packet header: 10 bytes
        if (value.size() <= 10)
            return;

        uint16_t trackId =
            (uint8_t)value[0] |
            ((uint8_t)value[1] << 8);

        uint16_t transferId =
            (uint8_t)value[2] |
            ((uint8_t)value[3] << 8);

        uint16_t seq =
            (uint8_t)value[4] |
            ((uint8_t)value[5] << 8);

        uint16_t total =
            (uint8_t)value[6] |
            ((uint8_t)value[7] << 8);

        uint16_t chunkSize =
            (uint8_t)value[8] |
            ((uint8_t)value[9] << 8);

        if (
            trackId == 0 ||
            total == 0 ||
            total > 40 ||
            seq >= total ||
            chunkSize == 0 ||
            chunkSize > ART_MAX_CHUNK_DATA
        )
            return;

        // Start of a new metadata transfer
        if (seq == 0) {

            metaTrackId = trackId;
            metaTransferId = transferId;

            metaExpectedChunks = total;
            metaReceivedChunks = 0;
            metaPacketDataSize = chunkSize;
            metaBytesReceived = 0;

            metaTransferActive = true;
        }

        // Reject packets from a different transfer
        if (
            !metaTransferActive ||
            trackId != metaTrackId ||
            transferId != metaTransferId ||
            total != metaExpectedChunks ||
            chunkSize != metaPacketDataSize ||
            seq != metaReceivedChunks
        )
            return;

        size_t offset = (size_t)seq * metaPacketDataSize;
        size_t payload = value.size() - 10;

        if (
            payload == 0 ||
            offset + payload > sizeof(metaBuffer)
        ) {
            metaTransferActive = false;
            return;
        }

        memcpy(
            metaBuffer + offset,
            value.data() + 10,
            payload
        );

        metaBytesReceived = offset + payload;
        metaReceivedChunks++;

        if (metaReceivedChunks != metaExpectedChunks)
            return;

        metaTransferActive = false;

        JsonDocument doc;

        DeserializationError err = deserializeJson(
            doc,
            metaBuffer,
            metaBytesReceived
        );

        if (err) {
            Serial.println("Metadata JSON parsing failed");
            return;
        }

        const char *title =
            doc["title"] | "Unknown title";

        const char *artist =
            doc["artist"] | "Unknown artist";

        uint32_t duration = doc["duration"] | 0;
        uint32_t position = doc["position"] | 0;
        bool playing = doc["playing"] | false;

        // Only a different track resets the artwork.
        bool newTrack = (trackId != activeTrackId);

        activeTrackId = trackId;

        trackDurationMs = duration;
        playbackPositionMs = position;
        isPlaying = playing;

        lastProgressUpdate = millis();

        snprintf(
            pendingTitle,
            sizeof(pendingTitle),
            "%s",
            title
        );

        snprintf(
            pendingArtist,
            sizeof(pendingArtist),
            "%s",
            artist
        );

        if (newTrack) {
            uiTrackChanged = true;
        }

        uiMetaPending = true;

        Serial.printf(
            "Metadata received: track=%u transfer=%u title=%s\n",
            trackId,
            transferId,
            title
        );
    }
};

// --------------------------------------------------
// ALBUM ART BLE CALLBACK
// --------------------------------------------------

class ArtCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &info) override {
        std::string value = c->getValue();

        if (value.size() <= 10) return;

        uint16_t trackId = (uint8_t)value[0] | ((uint8_t)value[1] << 8);
        uint16_t transferId = (uint8_t)value[2] | ((uint8_t)value[3] << 8);
        uint16_t seq = (uint8_t)value[4] | ((uint8_t)value[5] << 8);
        uint16_t total = (uint8_t)value[6] | ((uint8_t)value[7] << 8);
        uint16_t chunkSize = (uint8_t)value[8] | ((uint8_t)value[9] << 8);

        if (trackId == 0 ||
            trackId != activeTrackId ||
            total == 0 || total > 1400 ||
            seq >= total ||
            chunkSize == 0 || chunkSize > ART_MAX_CHUNK_DATA) {
            return;
        }

        if (seq == 0) {
            if (artBuffer == nullptr) {
                artBuffer = (uint8_t *)heap_caps_malloc(
                    ART_BYTES, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT
                );

                if (artBuffer == nullptr) {
                    artBuffer = (uint8_t *)malloc(ART_BYTES);
                }
            }

            if (artBuffer == nullptr) {
                Serial.println("Artwork buffer allocation failed");
                artTransferActive = false;
                return;
            }

            memset(artBuffer, 0, ART_BYTES);
            memset(artChunkSeen, 0, sizeof(artChunkSeen));

            artTrackId = trackId;
            artTransferId = transferId;
            artExpectedChunks = total;
            artReceivedChunks = 0;
            artPacketDataSize = chunkSize;
            artTransferActive = true;
        }

        if (!artTransferActive ||
            trackId != artTrackId ||
            transferId != artTransferId ||
            total != artExpectedChunks ||
            chunkSize != artPacketDataSize) {
            return;
        }

        size_t payloadSize = value.size() - 10;
        size_t offset = (size_t)seq * artPacketDataSize;

        if (payloadSize == 0 ||
            payloadSize > artPacketDataSize ||
            offset + payloadSize > ART_BYTES) {
            artTransferActive = false;
            return;
        }

        if (artChunkSeen[seq]) return;

        memcpy(artBuffer + offset, value.data() + 10, payloadSize);

        artChunkSeen[seq] = true;
        artReceivedChunks++;

        if (artReceivedChunks != artExpectedChunks) return;

        if (artTrackId != activeTrackId) {
            artTransferActive = false;
            return;
        }

        artTransferActive = false;
        uiArtPending = true;

        Serial.printf(
            "Artwork received: track=%u transfer=%u chunks=%u\n",
            artTrackId, artTransferId, artExpectedChunks
        );
    }
};

// --------------------------------------------------
// BLE INITIALIZATION
// --------------------------------------------------

static void startBle() {

    NimBLEDevice::init(DEVICE_NAME);

    NimBLEDevice::setMTU(247);

    NimBLEServer *server = NimBLEDevice::createServer();

    server->setCallbacks(new ServerCallbacks());

    NimBLEService *service = server->createService(
        SERVICE_UUID
    );

    metaCharacteristic = service->createCharacteristic(
        META_UUID,
        NIMBLE_PROPERTY::WRITE |
        NIMBLE_PROPERTY::WRITE_NR
    );

    artCharacteristic = service->createCharacteristic(
        ART_UUID,
        NIMBLE_PROPERTY::WRITE |
        NIMBLE_PROPERTY::WRITE_NR
    );

    commandCharacteristic = service->createCharacteristic(
        CMD_UUID,
        NIMBLE_PROPERTY::NOTIFY
    );

    metaCharacteristic->setCallbacks(
        new MetaCallbacks()
    );

    artCharacteristic->setCallbacks(
        new ArtCallbacks()
    );

    service->start();

    
    NimBLEAdvertising *advertising =
        NimBLEDevice::getAdvertising();

    // Enable scan response so the complete device name
    // can be advertised separately from the service UUID.
    advertising->enableScanResponse(true);

    bool uuidAdded = advertising->addServiceUUID(SERVICE_UUID);
    bool nameSet = advertising->setName(DEVICE_NAME);

    Serial.printf("Service UUID added: %s\n",
                  uuidAdded ? "YES" : "NO");

    Serial.printf("Device name added: %s\n",
                  nameSet ? "YES" : "NO");

    advertising->start();

    Serial.println("BLE advertising started.");
}

// --------------------------------------------------
// SETUP
// --------------------------------------------------

void setup() {

    Serial.begin(115200);

    Serial.printf("PSRAM found: %s\n", psramFound() ? "YES" : "NO");
    Serial.printf("PSRAM size: %u bytes\n", ESP.getPsramSize());
    Serial.printf("PSRAM free: %u bytes\n", ESP.getFreePsram());
    Serial.printf("Heap free: %u bytes\n", ESP.getFreeHeap());

    // Display backlight

    pinMode(TFT_BL, OUTPUT);

    digitalWrite(TFT_BL, HIGH);

    // Touch reset

    pinMode(TP_RST, OUTPUT);

    digitalWrite(TP_RST, LOW);

    delay(5);

    digitalWrite(TP_RST, HIGH);

    delay(50);

    // Touch I2C

    Wire.begin(TP_SDA, TP_SCL);

    Wire.setClock(400000);

    // Display initialization

    if (!gfx->begin()) {

        Serial.println("Display init failed");

        while (true)
            delay(100);
    }

    gfx->fillScreen(0x0000);

    // LVGL initialization

    lv_init();

    // Allocate LVGL draw buffer

    draw_pixels = (lv_color_t *)heap_caps_malloc(
        sizeof(lv_color_t) * 240 * 40,
        MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT
    );

    if (!draw_pixels) {

        draw_pixels = (lv_color_t *)malloc(
            sizeof(lv_color_t) * 240 * 40
        );
    }

    if (!draw_pixels) {

        Serial.println("LVGL buffer allocation failed");

        while (true)
            delay(100);
    }

    lv_disp_draw_buf_init(
        &draw_buf,
        draw_pixels,
        nullptr,
        240 * 40
    );

    // Display driver

    lv_disp_drv_init(&disp_drv);

    disp_drv.hor_res = 240;

    disp_drv.ver_res = 240;

    disp_drv.flush_cb = [](
        lv_disp_drv_t *d,
        const lv_area_t *a,
        lv_color_t *p
    ) {

        uint32_t w = a->x2 - a->x1 + 1;

        uint32_t h = a->y2 - a->y1 + 1;

        gfx->draw16bitRGBBitmap(
            a->x1,
            a->y1,
            (uint16_t *)p,
            w,
            h
        );

        lv_disp_flush_ready(d);
    };

    disp_drv.draw_buf = &draw_buf;

    lv_disp_drv_register(&disp_drv);

    // Touch driver

    lv_indev_drv_init(&indev_drv);

    indev_drv.type = LV_INDEV_TYPE_POINTER;

    indev_drv.read_cb = touchRead;

    lv_indev_drv_register(&indev_drv);

    // Build interface

    buildUI();

    // BLE initialization

    startBle();

    Serial.println(
        "NowPlaying-S3 ready. Connect from Android app."
    );
}

// --------------------------------------------------
// MAIN LOOP
// --------------------------------------------------

void loop() {

    uint32_t now = millis();

    // LVGL tick

    lv_tick_inc(now - lastLvTick);

    lastLvTick = now;

    // Connection UI update

    if (uiConnectionPending) {

        uiConnectionPending = false;

        updateConnectionUI();
    }

    // Clear previous track artwork
    if (uiTrackChanged) {
        uiTrackChanged = false;

        // Cancel any incomplete artwork transfer
        artTransferActive = false;
        artExpectedChunks = 0;
        artReceivedChunks = 0;

        // Release the previous displayed artwork
        if (displayArtBuffer != nullptr) {
            free(displayArtBuffer);
            displayArtBuffer = nullptr;
        }

        // Clear the image while waiting for new artwork
        lv_img_set_src(artImage, nullptr);
    }

    // Metadata update

    if (uiMetaPending) {

        uiMetaPending = false;

        lv_label_set_text(
            titleLabel,
            pendingTitle
        );

        lv_label_set_text(
            artistLabel,
            pendingArtist
        );

        char t[16];

        formatTime(
            trackDurationMs,
            t,
            sizeof(t)
        );

        lv_label_set_text(
            durationLabel,
            t
        );

        updatePlayGlyph();

        updateProgressUI();
    }

    // Album artwork update

    if (uiArtPending) {

        uiArtPending = false;

        // Display artwork only if it belongs to the active track
        if (artBuffer != nullptr && artTrackId == activeTrackId) {

            if (displayArtBuffer != nullptr) {
                free(displayArtBuffer);
                displayArtBuffer = nullptr;
            }

            displayArtBuffer = artBuffer;
            artBuffer = nullptr;

            artDescriptor.header.always_zero = 0;
            artDescriptor.header.w = ART_W;
            artDescriptor.header.h = ART_H;
            artDescriptor.header.cf = LV_IMG_CF_TRUE_COLOR;

            artDescriptor.data_size = ART_BYTES;
            artDescriptor.data = displayArtBuffer;

            lv_img_set_src(
                artImage,
                &artDescriptor
            );

            // Maintain the full-screen background for each new track.
            lv_obj_center(artImage);
            lv_img_set_zoom(artImage, 256);

        } else {
            Serial.println("Discarding stale artwork");

            if (artBuffer != nullptr) {
                free(artBuffer);
                artBuffer = nullptr;
            }
        }
    }

    // LVGL task handler

    lv_timer_handler();

    // Playback progress update

    if (millis() - lastProgressUpdate >= 500) {

        updateProgressUI();
    }

    delay(5);
}