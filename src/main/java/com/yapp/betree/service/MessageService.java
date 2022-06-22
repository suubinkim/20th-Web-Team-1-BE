package com.yapp.betree.service;

import com.yapp.betree.domain.Folder;
import com.yapp.betree.domain.Message;
import com.yapp.betree.domain.User;
import com.yapp.betree.dto.request.MessageRequestDto;
import com.yapp.betree.dto.response.MessageBoxResponseDto;
import com.yapp.betree.dto.response.MessagePageResponseDto;
import com.yapp.betree.exception.BetreeException;
import com.yapp.betree.exception.ErrorCode;
import com.yapp.betree.repository.FolderRepository;
import com.yapp.betree.repository.MessageRepository;
import com.yapp.betree.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.yapp.betree.exception.ErrorCode.TREE_NOT_FOUND;
import static com.yapp.betree.exception.ErrorCode.USER_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageService {

    private final UserRepository userRepository;
    private final FolderRepository folderRepository;
    private final MessageRepository messageRepository;

    private static final int PAGE_SIZE = 7;

    /**
     * 칭찬 메세지 생성 (물 주기)
     *
     * @param senderId   발신유저아이디
     * @param requestDto messageRequestDto
     */
    @Transactional
    public void createMessage(Long senderId, MessageRequestDto requestDto) {

        //수신자 유저 객체 조회
        User user = userRepository.findById(requestDto.getReceiverId()).orElseThrow(() -> new BetreeException(USER_NOT_FOUND, "receiverId = " + requestDto.getReceiverId()));

        Folder folder = folderRepository.findById(requestDto.getFolderId()).orElseThrow(() -> new BetreeException(TREE_NOT_FOUND, "tree = " + requestDto.getFolderId()));

        Message message = Message.builder()
                .senderId(senderId)
                .user(user)
                .anonymous(requestDto.isAnonymous())
                .content(requestDto.getContent())
                .folder(folder)
                .build();

        //로그인 안 한 상태에서 메세지 전송시 익명 여부 true 설정
        if (senderId == 1L) {
            message.updateAnonymous();
        }

        // 본인에게 보낸 메세지일 때 읽음 여부 true 설정
        if (Objects.equals(senderId, requestDto.getReceiverId())) {
            message.updateAlreadyRead();
        }

        messageRepository.save(message);
    }

    /**
     * 메세지함 목록 조회
     *
     * @param userId
     */
    public MessagePageResponseDto getMessageList(Long userId, int page) {

        PageRequest pageRequest = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdDate"));

        //다음 페이지 존재 여부
        boolean hasNext = messageRepository.findByUserId(userId, pageRequest).hasNext();

        Slice<Message> messages = messageRepository.findByUserId(userId, pageRequest);

        List<MessageBoxResponseDto> responseDtos = new ArrayList<>();

        for (Message m : messages) {
            MessageBoxResponseDto message;
            if (m.isAnonymous()) {
                message = new MessageBoxResponseDto(m, "익명", "기본이미지");
            } else {
                message = new MessageBoxResponseDto(m, m.getUser().getNickname(), m.getUser().getUserImage());
            }
            responseDtos.add(message);
        }
        return new MessagePageResponseDto(responseDtos, hasNext);
    }

    /**
     * 선택한 메세지 공개로 설정 (열매 맺기)
     *
     * @param messageIdList 선택한 메세지 ID list
     */
    @Transactional
    public void updateMessageOpening(Long userId, List<Long> messageIdList) {
        //선택한 개수 8개 초과면 오류
        if (messageIdList.size() > 8) {
            throw new BetreeException(ErrorCode.INVALID_INPUT_VALUE, "열매로 맺을 수 있는 메세지 개수는 최대 8개입니다.");
        }
        //이미 선택된 메세지 가져와서 false로 변경
        List<Message> messages = messageRepository.findByUserIdAndOpening(userId, true);
        for (Message m : messages) {
            m.updateOpening();
        }
        // 지금 선택된 메세지만 true로 변경
        for (Long id : messageIdList) {
            Optional<Message> message = messageRepository.findById(id);
            message.ifPresent(Message::updateOpening);
        }
    }
}
