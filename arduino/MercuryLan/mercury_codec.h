#pragma once

#include <stddef.h>
#include <stdint.h>

namespace mercury {

static const size_t REQUEST_SIZE = 7;
static const size_t RESPONSE_SIZE = 23;
static const uint8_t READ_TARIFFS = 0x27;

enum Error {
  OK,
  BAD_LENGTH,
  WRONG_ADDRESS,
  WRONG_COMMAND,
  BAD_CRC,
  BAD_BCD,
  NO_REPLY,
  INVALID_FRAME,
  NOT_POLLED,
  SERIAL_OVERFLOW
};

uint16_t crc16(const uint8_t* data, size_t length);
void makeRequest(uint32_t address, uint8_t out[REQUEST_SIZE]);
Error decodeResponse(const uint8_t* frame, size_t length,
                     uint32_t address, uint32_t centiKwh[4]);

// A moving 23-byte window ignores request echo, unrelated bytes and partial
// frames. Only a fully validated reply for the requested address is accepted.
class Decoder {
 public:
  explicit Decoder(uint32_t address);
  bool feed(uint8_t byte, uint32_t centiKwh[4]);
  Error failure() const;

 private:
  uint32_t address_;
  uint8_t window_[RESPONSE_SIZE];
  uint8_t count_;
  bool sawBytes_;
  Error lastRelevantError_;
};

}  // namespace mercury
