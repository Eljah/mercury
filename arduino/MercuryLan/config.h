#pragma once

// Все пользовательские настройки находятся в этом файле.
// 651401 = последние 6 цифр номера 00651401 на фотографии.
#define METER_ADDRESS 651401UL
#define METER_BAUD 9600UL
#define METER_RX_PIN 8
#define METER_TX_PIN 9
#define METER_POLL_MS 30000UL
#define METER_REPLY_TIMEOUT_MS 700UL

#define ETHERNET_CS_PIN 10
#define UNUSED_SD_CS_PIN 4
#define HTTP_PORT 80
#define HTTP_READ_TIMEOUT_MS 700UL
#define HTTP_MAX_HEADER_BYTES 1024U

// 0: статический IP; 1: DHCP с ограниченным временем ожидания.
// Если DHCP не ответит, используется этот же статический адрес.
#define USE_DHCP 0
#define DHCP_TIMEOUT_MS 5000UL
#define DHCP_RESPONSE_TIMEOUT_MS 1000UL
#define ETHERNET_MAC {0x02, 0x4D, 0x45, 0x52, 0x00, 0x01}
#define STATIC_IP {192, 168, 1, 50}
#define STATIC_GATEWAY {192, 168, 1, 1}
#define STATIC_DNS {192, 168, 1, 1}
#define STATIC_SUBNET {255, 255, 255, 0}

// Настройка трансивера проверена в документации только для 9600 бод.
#if METER_BAUD != 9600UL
#error "Use 9600 baud; verify the CAN PHY dominant-timeout before changing it"
#endif
#if METER_RX_PIN == METER_TX_PIN
#error "RX and TX must use different pins"
#endif
#if METER_RX_PIN < 2 || METER_TX_PIN < 2 || METER_RX_PIN >= 10 || METER_TX_PIN >= 10
#error "Meter pins must not overlap USB serial D0/D1 or SPI D10..D13"
#endif
#if METER_RX_PIN == UNUSED_SD_CS_PIN || METER_TX_PIN == UNUSED_SD_CS_PIN
#error "Meter pins must not overlap the shield SD chip-select pin"
#endif
#if METER_POLL_MS < 1000UL || METER_POLL_MS > 3600000UL
#error "Poll period must be between 1 second and 1 hour"
#endif
#if METER_REPLY_TIMEOUT_MS < 100UL || METER_REPLY_TIMEOUT_MS > 5000UL
#error "Reply timeout must be between 100 and 5000 ms"
#endif
