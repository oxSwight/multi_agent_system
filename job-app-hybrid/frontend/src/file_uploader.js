const ALLOWED_TYPES = [
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
];

const ALLOWED_EXTENSIONS = ['.pdf', '.docx'];

function isAllowedResume(file) {
  if (!file) {
    return false;
  }
  const lowerName = file.name.toLowerCase();
  const hasAllowedExtension = ALLOWED_EXTENSIONS.some((ext) => lowerName.endsWith(ext));
  return ALLOWED_TYPES.includes(file.type) || hasAllowedExtension;
}

/**
 * @param {File} file
 * @returns {Promise<object>}
 */
async function uploadResume(file) {
  const formData = new FormData();
  formData.append('file', file);

  const baseUrl = window.API_BASE_URL || 'http://localhost:8080';
  const response = await fetch(`${baseUrl}/api/files`, {
    method: 'POST',
    body: formData,
  });

  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = payload.message || payload.error || 'Upload failed.';
    throw new Error(message);
  }
  return payload;
}

window.initFileUploader = function initFileUploader() {
  const uploadBtn = document.getElementById('upload-btn');
  const fileInput = document.getElementById('resume-upload');
  if (!uploadBtn || !fileInput) {
    return;
  }

  uploadBtn.addEventListener('click', async () => {
    const file = fileInput.files[0];
    const showStatus = window.showStatus;

    if (!file) {
      showStatus('Select a PDF or DOCX resume first.', 'error');
      return;
    }

    if (!isAllowedResume(file)) {
      showStatus('Only PDF and DOCX files are supported.', 'error');
      return;
    }

    uploadBtn.disabled = true;
    showStatus('Uploading resume…', 'info');

    try {
      const result = await uploadResume(file);
      const fileName = result.fileName || file.name;
      showStatus(`Resume uploaded: ${fileName}`, 'success');
      fileInput.value = '';
    } catch (error) {
      console.error('Resume upload failed:', error);
      showStatus(error.message || 'Network error. Is the backend running?', 'error');
    } finally {
      uploadBtn.disabled = false;
    }
  });
};

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { initFileUploader: window.initFileUploader, uploadResume, isAllowedResume };
}
