#include <SPI.h>
#include <Ethernet.h>
#include <SoftwareSerial.h>
#include <string.h>

#include "config.h"
#include "mercury_codec.h"

SoftwareSerial meterSerial(METER_RX_PIN, METER_TX_PIN);
EthernetServer httpServer(HTTP_PORT);

static bool hasReading = false;
static bool lastPollOk = false;
static bool dhcpActive = false;
static uint32_t lastGoodMs = 0;
static uint32_t lastPollMs = 0;
static uint32_t tariffs[4] = {0, 0, 0, 0};
static mercury::Error lastError = mercury::NOT_POLLED;

// Buffer Print output so flash strings and numbers do not create one-byte TCP
// writes. No dynamic String, heap-allocated JSON, or floating-point formatting.
class BufferedReply : public Print {
 public:
  explicit BufferedReply(EthernetClient& client) : client_(client), used_(0) {}
  size_t write(uint8_t byte) override {
    data_[used_++] = byte;
    if (used_ == sizeof(data_)) flushBuffer();
    return 1;
  }
  void flushBuffer() {
    if (used_ != 0) {
      client_.write(data_, used_);
      used_ = 0;
    }
  }
 private:
  EthernetClient& client_;
  uint8_t data_[64];
  uint8_t used_;
};

void printError(Print& out, mercury::Error error) {
  switch (error) {
    case mercury::OK: out.print(F("null")); break;
    case mercury::BAD_CRC: out.print(F("\"crc_error\"")); break;
    case mercury::BAD_BCD: out.print(F("\"invalid_bcd\"")); break;
    case mercury::NOT_POLLED: out.print(F("\"not_polled\"")); break;
    case mercury::NO_REPLY: out.print(F("\"timeout\"")); break;
    case mercury::SERIAL_OVERFLOW: out.print(F("\"serial_overflow\"")); break;
    default: out.print(F("\"invalid_frame\"")); break;
  }
}

void printKwh(Print& out, uint32_t centiKwh) {
  out.print(centiKwh / 100UL);
  out.print('.');
  const uint8_t fraction = centiKwh % 100UL;
  if (fraction < 10) out.print('0');
  out.print(fraction);
}

void pollMeter() {
  lastPollMs = millis();
  meterSerial.listen();
  // Clear only bytes that predate this request. Never flush after sending:
  // the transceiver may echo TX, but an immediate real reply must be retained.
  const uint32_t drainStart = millis();
  while (meterSerial.available() && uint32_t(millis() - drainStart) < 10UL) {
    meterSerial.read();
  }
  meterSerial.overflow();

  uint8_t request[mercury::REQUEST_SIZE];
  mercury::makeRequest(METER_ADDRESS, request);
  mercury::Decoder decoder(METER_ADDRESS);
  meterSerial.write(request, sizeof(request));
  const uint32_t start = millis();
  uint32_t nextTariffs[4];
  while (uint32_t(millis() - start) < METER_REPLY_TIMEOUT_MS) {
    while (meterSerial.available()) {
      const uint8_t byte = uint8_t(meterSerial.read());
      if (decoder.feed(byte, nextTariffs)) {
        if (meterSerial.overflow()) {
          lastPollOk = false;
          lastError = mercury::SERIAL_OVERFLOW;
          return;
        }
        memcpy(tariffs, nextTariffs, sizeof(tariffs));
        hasReading = true;
        lastPollOk = true;
        lastError = mercury::OK;
        lastGoodMs = millis();
        return;
      }
    }
  }
  lastPollOk = false;
  lastError = meterSerial.overflow() ? mercury::SERIAL_OVERFLOW : decoder.failure();
}

void writeReadings(EthernetClient& client) {
  BufferedReply out(client);
  if (lastPollOk) out.println(F("HTTP/1.1 200 OK"));
  else out.println(F("HTTP/1.1 503 Service Unavailable"));
  out.println(F("Content-Type: application/json; charset=utf-8"));
  out.println(F("Cache-Control: no-store"));
  out.println(F("Connection: close"));
  out.println();
  out.print(F("{\"schema_version\":1,\"device\":\"mercury-200\",\"transport\":\"arduino-uart-can\",\"demo\":false,\"meter_address\":"));
  out.print(uint32_t(METER_ADDRESS));
  out.print(F(",\"ok\":"));
  out.print(lastPollOk ? F("true") : F("false"));
  out.print(F(",\"error\":"));
  printError(out, lastError);
  out.print(F(",\"has_reading\":"));
  out.print(hasReading ? F("true") : F("false"));
  out.print(F(",\"age_seconds\":"));
  if (hasReading) out.print(uint32_t(millis() - lastGoodMs) / 1000UL);
  else out.print(F("null"));
  out.print(F(",\"captured_at\":null,\"tariffs_kwh\":"));
  if (hasReading) {
    out.print('[');
    uint32_t total = 0;
    for (uint8_t i = 0; i < 4; ++i) {
      if (i) out.print(',');
      printKwh(out, tariffs[i]);
      total += tariffs[i];  // Four BCD8 values: max 399999996, fits uint32_t.
    }
    out.print(F("],\"total_kwh\":"));
    printKwh(out, total);
  } else {
    out.print(F("null,\"total_kwh\":null"));
  }
  out.println('}');
  out.flushBuffer();
}

void writeHttpError(EthernetClient& client, uint16_t status) {
  BufferedReply out(client);
  if (status == 404) out.println(F("HTTP/1.1 404 Not Found"));
  else if (status == 405) out.println(F("HTTP/1.1 405 Method Not Allowed"));
  else if (status == 414) out.println(F("HTTP/1.1 414 URI Too Long"));
  else out.println(F("HTTP/1.1 400 Bad Request"));
  out.println(F("Content-Type: application/json"));
  out.println(F("Connection: close"));
  if (status == 405) out.println(F("Allow: GET"));
  out.println();
  out.println(F("{\"ok\":false,\"error\":\"http_request\"}"));
  out.flushBuffer();
}

void serveHttp() {
  EthernetClient client = httpServer.available();
  if (!client) return;
  client.setConnectionTimeout(200);

  char firstLine[96];
  uint8_t used = 0;
  uint16_t headerBytes = 0;
  bool firstDone = false;
  bool blankLine = true;
  bool complete = false;
  bool tooLong = false;
  const uint32_t started = millis();
  while (client.connected() &&
         uint32_t(millis() - started) < HTTP_READ_TIMEOUT_MS &&
         headerBytes < HTTP_MAX_HEADER_BYTES && !complete && !tooLong) {
    if (!client.available()) continue;
    const char ch = char(client.read());
    ++headerBytes;
    if (!firstDone) {
      if (ch == '\n') {
        firstLine[used] = '\0';
        firstDone = true;
        blankLine = true;
      } else if (ch != '\r') {
        if (used >= sizeof(firstLine) - 1) tooLong = true;
        else firstLine[used++] = ch;
      }
    } else if (ch == '\n') {
      if (blankLine) complete = true;
      blankLine = true;
    } else if (ch != '\r') {
      blankLine = false;
    }
  }

  if (tooLong) writeHttpError(client, 414);
  else if (!complete) writeHttpError(client, 400);
  else if (strncmp(firstLine, "GET ", 4) != 0) writeHttpError(client, 405);
  else {
    char* path = firstLine + 4;
    char* space = strchr(path, ' ');
    if (!space || (strcmp(space + 1, "HTTP/1.1") != 0 &&
                   strcmp(space + 1, "HTTP/1.0") != 0)) {
      writeHttpError(client, 400);
    } else {
      *space = '\0';
      if (strcmp(path, "/api/v1/readings") == 0 || strcmp(path, "/health") == 0) {
        writeReadings(client);
      } else {
        writeHttpError(client, 404);
      }
    }
  }
  client.stop();
}

void setup() {
  Serial.begin(115200);
  // Deassert the optional SD card on Ethernet shields before using SPI.
  pinMode(UNUSED_SD_CS_PIN, OUTPUT);
  digitalWrite(UNUSED_SD_CS_PIN, HIGH);
  meterSerial.begin(METER_BAUD);
  Ethernet.init(ETHERNET_CS_PIN);
  uint8_t mac[] = ETHERNET_MAC;
  const uint8_t ipBytes[] = STATIC_IP;
  const uint8_t gwBytes[] = STATIC_GATEWAY;
  const uint8_t dnsBytes[] = STATIC_DNS;
  const uint8_t subnetBytes[] = STATIC_SUBNET;
#if USE_DHCP
  dhcpActive = Ethernet.begin(mac, DHCP_TIMEOUT_MS, DHCP_RESPONSE_TIMEOUT_MS) != 0;
#endif
  if (!dhcpActive) {
    Ethernet.begin(mac, IPAddress(ipBytes), IPAddress(dnsBytes),
                   IPAddress(gwBytes), IPAddress(subnetBytes));
  }
  Ethernet.setRetransmissionTimeout(200);
  Ethernet.setRetransmissionCount(2);
  httpServer.begin();
  Serial.print(F("Mercury LAN IP: "));
  Serial.println(Ethernet.localIP());
  if (Ethernet.hardwareStatus() == EthernetNoHardware) {
    Serial.println(F("ERROR: W5500 not detected; check power, SPI and CS"));
  }
  pollMeter();
}

void loop() {
  if (uint32_t(millis() - lastPollMs) >= METER_POLL_MS) pollMeter();
  serveHttp();
  if (dhcpActive) Ethernet.maintain();
}
