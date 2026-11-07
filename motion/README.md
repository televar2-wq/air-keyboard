# Motion Puppet

Records body movement with the phone camera (up to two people) as skeleton data only —
no video, no background, no faces — then puts a rigged 3D character (Meshy/Mixamo GLB)
on the movement. Play it back, orbit the camera, and save a video to Movies/Motion Puppet.

- Capture: CameraX + MediaPipe Pose Landmarker (33 body points, 3D world coordinates).
- Stage: three.js in a WebView; bones are aimed along the recorded limb directions.
- Built by `.github/workflows/build-motion.yml`; APKs are published as `motion-build-N` releases.
