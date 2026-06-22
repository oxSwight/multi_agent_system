/**
 * Popup entry point — initializes profile and upload modules after DOM is ready.
 */
document.addEventListener('DOMContentLoaded', () => {
  if (typeof window.initProfileManager === 'function') {
    window.initProfileManager();
  }
  if (typeof window.initFileUploader === 'function') {
    window.initFileUploader();
  }
});
