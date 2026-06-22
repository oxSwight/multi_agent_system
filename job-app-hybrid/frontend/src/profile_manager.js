const PROFILE_STORAGE_KEY = 'userProfile';
const API_BASE_URL = 'http://localhost:8080';

/**
 * Shows an inline status message in the popup (no alert/prompt).
 * @param {string} message
 * @param {'success'|'error'|'info'} type
 */
function showStatus(message, type) {
  const toast = document.getElementById('status-toast');
  if (!toast) {
    return;
  }
  toast.textContent = message;
  toast.className = 'rounded-md border px-3 py-2 text-xs is-visible';
  toast.classList.add(type === 'success' ? 'is-success' : type === 'error' ? 'is-error' : 'is-info');
}

/**
 * @returns {Promise<{name: string, email: string, phone: string}>}
 */
async function loadProfile() {
  const result = await chrome.storage.local.get(PROFILE_STORAGE_KEY);
  const profile = result[PROFILE_STORAGE_KEY];
  if (profile && typeof profile === 'object') {
    return {
      name: profile.name || '',
      email: profile.email || '',
      phone: profile.phone || '',
    };
  }
  return { name: '', email: '', phone: '' };
}

/**
 * @param {{name: string, email: string, phone: string}} profile
 */
async function saveProfile(profile) {
  await chrome.storage.local.set({ [PROFILE_STORAGE_KEY]: profile });
}

function readProfileFromForm() {
  return {
    name: document.getElementById('profile-name').value.trim(),
    email: document.getElementById('profile-email').value.trim(),
    phone: document.getElementById('profile-phone').value.trim(),
  };
}

function fillProfileForm(profile) {
  document.getElementById('profile-name').value = profile.name;
  document.getElementById('profile-email').value = profile.email;
  document.getElementById('profile-phone').value = profile.phone;
}

async function handleSaveProfile() {
  const profile = readProfileFromForm();
  if (!profile.name) {
    showStatus('Please enter your full name before saving.', 'error');
    return;
  }
  try {
    await saveProfile(profile);
    showStatus('Profile saved locally.', 'success');
  } catch (error) {
    console.error('Failed to save profile:', error);
    showStatus('Could not save profile. Try again.', 'error');
  }
}

window.initProfileManager = async function initProfileManager() {
  const saveBtn = document.getElementById('save-profile-btn');
  if (!saveBtn) {
    return;
  }

  try {
    const profile = await loadProfile();
    fillProfileForm(profile);
  } catch (error) {
    console.error('Failed to load profile:', error);
    showStatus('Could not load saved profile.', 'error');
  }

  saveBtn.addEventListener('click', handleSaveProfile);
};

window.showStatus = showStatus;
window.API_BASE_URL = API_BASE_URL;

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { initProfileManager: window.initProfileManager, loadProfile, saveProfile };
}
