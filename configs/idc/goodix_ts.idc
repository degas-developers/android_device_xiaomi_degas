# Goodix touchscreen registers as a virtual input device
# (/devices/virtual/input). Mark it internal so InputReader associates it
# with the built-in display and keeps its viewport active.
device.internal = 1
touch.deviceType = touchScreen
touch.orientationAware = 1
