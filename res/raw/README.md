The JSON files in this directory can be overridden by vendors using Runtime
Resource Overlays. These serve as an optional mechanism for vendors to modify
DeviceAsWebcam behavior if needed.

The details of the overridable files are as follows:

- `physical_camera_mapping.json`:

  This file contains a mapping of the preferred physical streams that are to be
  used by DeviceAsWebcam service instead of logical streams, in order to save
  power during webcam streaming. This mapping is optional. It must be overridden
  through a static resource overlay by vendors if needed. The format of the
  mapping is :

  ```json
  {
    "<logical-camera-id-1>" : {
      "<physical-camera-id-1>" : "<camera-label-1>",
      "<physical-camera-id-2>" : "<camera-label-2>",
      ...
    },
    "<logical-camera-id-2>" : {
      "<physical-camera-id-3>" : "<camera-label-3>",
      "<physical-camera-id-4>" : "<camera-label-4>",
      ...
    },
    ...
  }
  ```

  The 'camera-label' field here helps DeviceAsWebcam label these physical camera
  ids in the UI elements of the service. The mapping containing physical camera
  ids is in order of preference of the physical streams that must be used by
  DeviceAsWebcam service.

  For example, if a vendor would like to advertise 2 possible physical streams
  with camera ids 3(Ultra-wide) and 4(Wide) for the back logical camera the
  mapping could be:
  ```json
  {
    "0" : {
      "3" : "UW",
      "4" : "W"
    }
  }
  ```

- `ignored_v4l2_nodes.json`:

  Linux UVC gadget driver mounts a V4L2 node that DeviceAsWebcam service
  interacts with. To determine this node, DeviceAsWebcam service looks through
  all `/dev/video*` nodes, looking for the first V4L2 node that advertises
  `V4L2_CAP_VIDEO_OUTPUT` capability.

  This will run into issues if a device has other V4L2 nodes mounted at
  `/dev/video*` with `V4L2_CAP_VIDEO_OUTPUT` capability advertised for other
  processing purposes.

  `ignored_v4l2_nodes.json` provides a way for DeviceAsWebcam service to
  ignore a predetermined set of V4L2 nodes.

  The file format is as follows:

  ```json
  [
      "/dev/video10",
      "/dev/video12",
      ...
  ]
  ```

  Note that wildcard patterns are _not_ supported in `ignored_v4l2_nodes.json`.
