'use strict'
;(function () {
  var body = $.GetContextPanel().FindChildrenWithClassTraverse('MainBody')[0]
  if (!body) return $.Msg('Hideout: the Custom Match popup has no MainBody')
  $.CreatePanel('Panel', body, 'HideoutBand').BLoadLayout('file://{resources}/layout/hideout/band.xml', false, false)
})()
