ui_print "- Rejestrator Rozmów: instalacja jako priv-app"
ui_print "- Uprawnienie: CAPTURE_AUDIO_OUTPUT (nagrywanie linii)"
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
ui_print "- Uruchom telefon ponownie, aby aktywować"
