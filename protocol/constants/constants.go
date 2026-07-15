// Package constants holds protocol-level values shared across all language implementations.
// Each language's generated/ directory re-exports these — edit here, not there.
package constants

const (
	DefaultPort    = 8765
	MaxRetries     = 60
	RetryDelayMs   = 5000

	BluetoothServiceName      = "PlayBridgeRemote"
	BluetoothServiceUUID      = "a8f5f167-f92d-4a28-9f02-5f8d9b5c6b4e"

	NsdServiceType    = "_playbridge._tcp."
	NsdKeyDeviceName  = "device_name"
	// NsdKeyWssPort is the TXT key advertising the receiver's wss:// port.
	// Absent when the receiver has no TLS listener.
	NsdKeyWssPort     = "wss_port"
	// NsdKeyLogsPort is the optional TXT key advertising an HTTP diagnostics endpoint.
	NsdKeyLogsPort    = "logs_port"
)
