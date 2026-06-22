/**
 * @jest-environment jsdom
 */

const { initProfileManager } = require('../../src/profile_manager.js');

describe('profile_manager', () => {
  beforeEach(() => {
    document.body.innerHTML = `
      <input id="profile-name" />
      <input id="profile-email" />
      <input id="profile-phone" />
      <button id="save-profile-btn"></button>
      <div id="status-toast"></div>
    `;

    global.chrome = {
      storage: {
        local: {
          get: jest.fn().mockResolvedValue({ userProfile: { name: 'Ada', email: 'ada@test.com', phone: '555' } }),
          set: jest.fn().mockResolvedValue(undefined),
        },
      },
    };

    window.showStatus = jest.fn();
    window.initProfileManager = initProfileManager;
  });

  it('loads saved profile into the form', async () => {
    await initProfileManager();

    expect(document.getElementById('profile-name').value).toBe('Ada');
    expect(document.getElementById('profile-email').value).toBe('ada@test.com');
    expect(document.getElementById('profile-phone').value).toBe('555');
  });
});
