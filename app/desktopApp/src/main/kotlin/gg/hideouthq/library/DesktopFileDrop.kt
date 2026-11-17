package gg.hideouthq.library

import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.ui.FileImportState
import java.awt.Component
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File

class DesktopFileDrop(window: Component, private val intake: FileImportState) : AutoCloseable {
    private val previous = mutableMapOf<Component, DropTarget?>()
    private val listener = object : DropTargetAdapter() {
        private var hoveredFiles: List<LocalFile>? = null

        override fun dragEnter(event: DropTargetDragEvent) {
            hoveredFiles = null
            if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                event.acceptDrag(DnDConstants.ACTION_COPY)
                hoveredFiles = localFiles(event.transferable)
            }
            hover(event)
        }
        override fun dragOver(event: DropTargetDragEvent) = hover(event)
        override fun dropActionChanged(event: DropTargetDragEvent) = hover(event)
        override fun dragExit(event: DropTargetEvent) {
            hoveredFiles = null
            intake.leave()
        }

        override fun drop(event: DropTargetDropEvent) {
            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                event.rejectDrop()
                intake.leave()
                return
            }
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val files = localFiles(event.transferable)
            if (files == null) {
                intake.unreadable()
                event.dropComplete(false)
            } else {
                intake.drop(files)
                event.dropComplete(true)
            }
        }

        private fun hover(event: DropTargetDragEvent) {
            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                event.rejectDrag()
                intake.leave()
                return
            }
            event.acceptDrag(DnDConstants.ACTION_COPY)
            intake.hover(hoveredFiles)
        }
    }

    init {
        fun attach(component: Component) {
            previous[component] = component.dropTarget
            component.dropTarget = DropTarget(component, DnDConstants.ACTION_COPY, listener, true)
            if (component is Container) component.components.forEach(::attach)
        }
        attach(window)
    }

    override fun close() {
        previous.forEach { (component, target) -> component.dropTarget = target }
        previous.clear()
        intake.leave()
    }
}

internal fun localFiles(transferable: Transferable): List<LocalFile>? = runCatching {
    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: return null
    files.map { (it as? File ?: return null).asLocalFile() }
}.getOrNull()
