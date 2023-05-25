physical_camera_mapping.json:
This file contains a mapping of the preferred physical streams that are to be
used by DeviceAsWebcam service instead of logical streams, in order to save
power during webcam streaming. This mapping is optional. It must be overriden
through a static resource overlay by vendors if needed. The format of the
mapping is :
{"<logical-camera-id>" : {"<physical-camera-id>" : "camera label"}}

The 'camera-label' field here helps DeviceAsWebcam label these physical camera
ids in the UI elements of the service. The mapping containing physical camera
ids is in order of preference of the physical streams that must be used by
DeviceAsWebcam service.

For example, if a vendor would like to advertise 2 possible physical streams
with camera ids 3(Ultra-wide) and 4(Wide) for the back logical camera the mapping could be:

{"0" : {"3" : "UW", "4" : "W"}}
