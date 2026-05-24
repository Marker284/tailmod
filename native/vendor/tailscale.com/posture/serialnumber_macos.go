// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

//go:build cgo && darwin && !ios

// Stub for cross-compilation from Linux: CoreFoundation/IOKit unavailable.
// On a real Mac, tsnet reads the serial number via native IOKit.

package posture

import "tailscale.com/types/logger"

func GetSerialNumbers(_ logger.Logf) ([]string, error) {
	return nil, nil
}
