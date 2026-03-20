
# Neo Smart Controller Hubitat Integration

Repository containing Hubitat drivers for controlling Neo Smart Blinds via the local TCP interface.

## Drivers
- NeoController.groovy (parent controller)
- NeoRoom.groovy (room group device)
- NeoBlind.groovy (individual blind device)

## Features
- Local LAN control
- Parent-child device architecture
- Structured logging levels

## Installation
1. Import drivers into Hubitat.
2. Create a device using the **Neo Controller** driver.
3. Configure controller IP and prefix.
4. Run discovery to create child devices.
