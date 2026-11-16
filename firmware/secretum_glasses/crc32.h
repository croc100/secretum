#pragma once
#include <stddef.h>
#include <stdint.h>

// 표준 CRC-32 (zlib / java.util.zip.CRC32 와 동일). ROM 함수의 초기값 규약 차이를 피하려고 직접 구현.
static inline uint32_t crc32_std(const uint8_t* data, size_t len) {
  uint32_t crc = 0xFFFFFFFFu;
  for (size_t i = 0; i < len; i++) {
    crc ^= data[i];
    for (int b = 0; b < 8; b++) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
  }
  return ~crc;
}
