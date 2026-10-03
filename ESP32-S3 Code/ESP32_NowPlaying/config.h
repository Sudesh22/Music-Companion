#pragma once
#include <cstdint>

extern int currentBrightness;
extern int screenSleepTime;
extern uint8_t currentWatchFace;

extern uint32_t lastTouchTime;
extern bool screenSleeping;
extern bool notificationWakeActive;

// --------------------------------------------------
// Display
// --------------------------------------------------
#define TFT_CS     8
#define TFT_DC     7
#define TFT_RST    1
#define TFT_BL     2

#define TFT_SCLK   9
#define TFT_MOSI   10

// --------------------------------------------------
// Touch
// --------------------------------------------------
#define TP_SDA     6
#define TP_SCL     5
#define TP_INT     4
#define TP_RST     3

// --------------------------------------------------
// MAX30102 Interrupt
// --------------------------------------------------
#define MAX30102_INT 13

// --------------------------------------------------
// W25Q32
// --------------------------------------------------
#define FLASH_CS   11
#define FLASH_MISO 12

// --------------------------------------------------
// Config persistence
// --------------------------------------------------
void initConfig();
void loadConfig();
void saveConfig();

void saveBrightness();
void saveSleepTime();
void saveWatchFace();
void saveTheme();