/**
 * @jest-environment jsdom
 */

const { initFileUploader } = require('../../src/file_uploader.js');

describe('file_uploader', () => {
  beforeEach(() => {
    document.body.innerHTML = `
      <input id="resume-upload" type="file" />
      <button id="upload-btn"></button>
      <div id="status-toast"></div>
    `;

    window.showStatus = jest.fn();
    window.API_BASE_URL = 'http://localhost:8080';
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ success: true, fileName: 'resume.pdf' }),
    });

    initFileUploader();
  });

  it('posts multipart file field to /api/files', async () => {
    const file = new File(['%PDF'], 'resume.pdf', { type: 'application/pdf' });
    const input = document.getElementById('resume-upload');
    Object.defineProperty(input, 'files', { value: [file] });

    document.getElementById('upload-btn').click();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(fetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/files',
      expect.objectContaining({ method: 'POST' })
    );
    const formData = fetch.mock.calls[0][1].body;
    expect(formData.get('file')).toBe(file);
  });
});
