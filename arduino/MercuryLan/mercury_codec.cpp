#include "mercury_codec.h"

#include <string.h>

namespace mercury {

uint16_t crc16(const uint8_t* data, size_t length) {
  uint16_t crc = 0xFFFF;
  for (size_t i = 0; i < length; ++i) {
    crc ^= data[i];
    for (uint8_t bit = 0; bit < 8; ++bit) {
      crc = (crc & 1U) ? (crc >> 1) ^ 0xA001U : crc >> 1;
    }
  }
  return crc;
}

void makeRequest(uint32_t address, uint8_t out[REQUEST_SIZE]) {
  out[0] = uint8_t(address >> 24);
  out[1] = uint8_t(address >> 16);
  out[2] = uint8_t(address >> 8);
  out[3] = uint8_t(address);
  out[4] = READ_TARIFFS;
  const uint16_t crc = crc16(out, 5);
  out[5] = uint8_t(crc);
  out[6] = uint8_t(crc >> 8);
}

Error decodeResponse(const uint8_t* frame, size_t length,
                     uint32_t address, uint32_t centiKwh[4]) {
  if (length != RESPONSE_SIZE) return BAD_LENGTH;
  const uint32_t returnedAddress = (uint32_t(frame[0]) << 24) |
      (uint32_t(frame[1]) << 16) | (uint32_t(frame[2]) << 8) | frame[3];
  if (returnedAddress != address) return WRONG_ADDRESS;
  if (frame[4] != READ_TARIFFS) return WRONG_COMMAND;
  const uint16_t expected = crc16(frame, RESPONSE_SIZE - 2);
  const uint16_t received = uint16_t(frame[21]) | (uint16_t(frame[22]) << 8);
  if (expected != received) return BAD_CRC;

  // Do not expose partially decoded results if any tariff is malformed.
  uint32_t decoded[4] = {0, 0, 0, 0};
  for (uint8_t tariff = 0; tariff < 4; ++tariff) {
    for (uint8_t i = 0; i < 4; ++i) {
      const uint8_t b = frame[5 + 4 * tariff + i];
      const uint8_t hi = b >> 4;
      const uint8_t lo = b & 0x0F;
      if (hi > 9 || lo > 9) return BAD_BCD;
      decoded[tariff] = decoded[tariff] * 100UL + hi * 10U + lo;
    }
  }
  memcpy(centiKwh, decoded, sizeof(decoded));
  return OK;
}

Decoder::Decoder(uint32_t address)
    : address_(address), count_(0), sawBytes_(false), lastRelevantError_(NO_REPLY) {}

bool Decoder::feed(uint8_t byte, uint32_t centiKwh[4]) {
  sawBytes_ = true;
  if (count_ == RESPONSE_SIZE) {
    memmove(window_, window_ + 1, RESPONSE_SIZE - 1);
    --count_;
  }
  window_[count_++] = byte;
  if (count_ != RESPONSE_SIZE) return false;
  const Error result = decodeResponse(window_, RESPONSE_SIZE, address_, centiKwh);
  if (result == OK) return true;
  if (result == BAD_CRC || result == BAD_BCD) lastRelevantError_ = result;
  return false;
}

Error Decoder::failure() const {
  if (lastRelevantError_ != NO_REPLY) return lastRelevantError_;
  return sawBytes_ ? INVALID_FRAME : NO_REPLY;
}

}  // namespace mercury
