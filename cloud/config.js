// cloud/config.js — deploy-time switches for cloud/main.js. Upload it next to main.js (main.js falls back to the
// defaults below when the file is absent).
//
// SETUP_ENABLED: while true, anyone holding the Application ID (and REST/Client key) can call the Cloud function
// `setupSchema`, which (re)applies the classes, fields and class-level permissions this app needs — it adds, never
// removes, and never touches indexes. It exists so a backend can be set up without the master key ever leaving the
// dashboard. Set it to false and redeploy before real users sign up.
module.exports = {
  SETUP_ENABLED: true,
};
